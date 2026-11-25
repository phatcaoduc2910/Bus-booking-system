package com.example.busbooking.business.service;

import com.example.busbooking.business.port.TransactionRunner;
import com.example.busbooking.business.port.repository.BookingIdempotencyRepository;
import com.example.busbooking.business.port.repository.BookingIdempotencyRepository.ClaimResult;
import com.example.busbooking.business.result.IdempotentBookingResult;
import com.example.busbooking.domain.enums.BookingStatus;
import com.example.busbooking.domain.exception.IdempotencyInProgressException;
import com.example.busbooking.domain.exception.IdempotencyKeyMismatchException;
import com.example.busbooking.domain.exception.InsufficientSeatsException;
import com.example.busbooking.domain.model.Booking;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentBookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-25T01:00:00Z");
    private static final String KEY = "key-1";

    private final FakeTransactionRunner tx = new FakeTransactionRunner();
    private final FakeKeys keys = new FakeKeys(tx);
    private final FakeBookingService bookings = new FakeBookingService(tx);
    private final IdempotentBookingService service =
            new IdempotentBookingService(bookings, keys, tx, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void withoutKeyDelegatesDirectly() {
        IdempotentBookingResult result = service.create(7L, null, 1L, "An", "0900000000", 2);

        assertThat(result.replayed()).isFalse();
        assertThat(result.booking()).isSameAs(bookings.created);
        assertThat(keys.calls).isEmpty();
    }

    @Test
    void firstRequestClaimsInNewTransactionThenCreatesAndCompletesTogether() {
        IdempotentBookingResult result = service.create(7L, KEY, 1L, "An", "0900000000", 2);

        assertThat(result.replayed()).isFalse();
        assertThat(result.booking()).isSameAs(bookings.created);
        assertThat(keys.calls).containsExactly("claim@NEW", "complete@REQUIRED");
        assertThat(bookings.createdIn).isEqualTo("REQUIRED");
        assertThat(keys.claimedHash).isEqualTo(BookingRequestHasher.hash(1L, "An", "0900000000", 2));
        assertThat(keys.claimedNow).isEqualTo(NOW);
        assertThat(keys.claimedStaleAt).isEqualTo(NOW.plusSeconds(60));
        assertThat(keys.claimedExpiresAt).isEqualTo(NOW.plusSeconds(24 * 3600));
        assertThat(keys.completedToken).isEqualTo(keys.claimedToken);
    }

    @Test
    void completedKeyReplaysStoredBookingWithoutCreating() {
        Booking stored = booking(99L);
        keys.nextClaim = new ClaimResult.Completed(stored);

        IdempotentBookingResult result = service.create(7L, KEY, 1L, "An", "0900000000", 2);

        assertThat(result.replayed()).isTrue();
        assertThat(result.booking()).isSameAs(stored);
        assertThat(bookings.created).isNull();
        assertThat(keys.calls).containsExactly("claim@NEW");
    }

    @Test
    void inProgressKeyIsRejected() {
        keys.nextClaim = new ClaimResult.InProgress();

        assertThatThrownBy(() -> service.create(7L, KEY, 1L, "An", "0900000000", 2))
                .isInstanceOf(IdempotencyInProgressException.class);
        assertThat(bookings.created).isNull();
    }

    @Test
    void payloadMismatchIsRejected() {
        keys.nextClaim = new ClaimResult.PayloadMismatch();

        assertThatThrownBy(() -> service.create(7L, KEY, 1L, "An", "0900000000", 2))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
        assertThat(bookings.created).isNull();
    }

    @Test
    void businessFailureReleasesKeyWithSameTokenAndRethrows() {
        bookings.failure = new InsufficientSeatsException("Not enough available seats");

        assertThatThrownBy(() -> service.create(7L, KEY, 1L, "An", "0900000000", 2))
                .isSameAs(bookings.failure);
        assertThat(tx.rolledBack).isTrue();
        assertThat(keys.calls).containsExactly("claim@NEW", "release@NEW");
        assertThat(keys.releasedToken).isEqualTo(keys.claimedToken);
    }

    @Test
    void lostOwnershipRollsBackBookingAndRejects() {
        keys.completeResult = false;

        assertThatThrownBy(() -> service.create(7L, KEY, 1L, "An", "0900000000", 2))
                .isInstanceOf(IdempotencyInProgressException.class);
        assertThat(tx.rolledBack).isTrue();
        assertThat(keys.calls).containsExactly("claim@NEW", "complete@REQUIRED", "release@NEW");
    }

    private static Booking booking(long id) {
        return new Booking(id, "BK-" + id, 7L, 1L, "An", "0900000000", 2,
                new BigDecimal("100000"), new BigDecimal("200000"),
                BookingStatus.CONFIRMED, LocalDateTime.of(2026, 11, 25, 8, 0));
    }

    private static final class FakeTransactionRunner implements TransactionRunner {
        String current = "NONE";
        boolean rolledBack;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return run("REQUIRED", work);
        }

        @Override
        public <T> T inReadOnlyTransaction(Supplier<T> work) {
            return run("READ_ONLY", work);
        }

        @Override
        public <T> T inNewTransaction(Supplier<T> work) {
            return run("NEW", work);
        }

        private <T> T run(String mode, Supplier<T> work) {
            String outer = current;
            current = mode;
            try {
                return work.get();
            } catch (RuntimeException e) {
                rolledBack = true;
                throw e;
            } finally {
                current = outer;
            }
        }
    }

    private static final class FakeKeys implements BookingIdempotencyRepository {
        private final FakeTransactionRunner tx;
        final List<String> calls = new ArrayList<>();
        ClaimResult nextClaim = new ClaimResult.Claimed();
        boolean completeResult = true;
        String claimedHash;
        UUID claimedToken;
        Instant claimedNow;
        Instant claimedStaleAt;
        Instant claimedExpiresAt;
        UUID completedToken;
        UUID releasedToken;

        FakeKeys(FakeTransactionRunner tx) {
            this.tx = tx;
        }

        @Override
        public ClaimResult claim(long userId, String idempotencyKey, String requestHash, UUID attemptToken,
                                 Instant now, Instant staleAt, Instant expiresAt) {
            calls.add("claim@" + tx.current);
            claimedHash = requestHash;
            claimedToken = attemptToken;
            claimedNow = now;
            claimedStaleAt = staleAt;
            claimedExpiresAt = expiresAt;
            return nextClaim;
        }

        @Override
        public boolean complete(long userId, String idempotencyKey, UUID attemptToken, Booking booking) {
            calls.add("complete@" + tx.current);
            completedToken = attemptToken;
            return completeResult;
        }

        @Override
        public void release(long userId, String idempotencyKey, UUID attemptToken) {
            calls.add("release@" + tx.current);
            releasedToken = attemptToken;
        }

        @Override
        public int deleteExpired(Instant now) {
            return 0;
        }
    }

    private static final class FakeBookingService extends BookingService {
        private final FakeTransactionRunner tx;
        Booking created;
        String createdIn;
        RuntimeException failure;

        FakeBookingService(FakeTransactionRunner tx) {
            super(null, null);
            this.tx = tx;
        }

        @Override
        public Booking create(Long userId, Long tripId, String passengerName,
                              String passengerPhone, int quantity) {
            createdIn = tx.current;
            if (failure != null) {
                throw failure;
            }
            created = booking(1L);
            return created;
        }
    }
}
