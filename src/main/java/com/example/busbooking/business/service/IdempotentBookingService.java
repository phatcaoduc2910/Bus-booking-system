package com.example.busbooking.business.service;

import com.example.busbooking.business.port.TransactionRunner;
import com.example.busbooking.business.port.repository.BookingIdempotencyRepository;
import com.example.busbooking.business.port.repository.BookingIdempotencyRepository.ClaimResult;
import com.example.busbooking.business.result.IdempotentBookingResult;
import com.example.busbooking.domain.exception.IdempotencyInProgressException;
import com.example.busbooking.domain.exception.IdempotencyKeyMismatchException;
import com.example.busbooking.domain.model.Booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class IdempotentBookingService {
    static final Duration STALE_AFTER = Duration.ofSeconds(60);
    static final Duration EXPIRES_AFTER = Duration.ofHours(24);

    private final BookingService bookingService;
    private final BookingIdempotencyRepository keys;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public IdempotentBookingService(BookingService bookingService, BookingIdempotencyRepository keys,
                                    TransactionRunner transactionRunner, Clock clock) {
        this.bookingService = bookingService;
        this.keys = keys;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
    }

    public IdempotentBookingResult create(Long userId, String idempotencyKey, Long tripId,
                                          String passengerName, String passengerPhone, int quantity) {
        if (idempotencyKey == null) {
            Booking booking = bookingService.create(userId, tripId, passengerName, passengerPhone, quantity);
            return new IdempotentBookingResult(booking, false);
        }

        String requestHash = BookingRequestHasher.hash(tripId, passengerName, passengerPhone, quantity);
        UUID attemptToken = UUID.randomUUID();
        Instant now = clock.instant();

        ClaimResult claim = transactionRunner.inNewTransaction(() -> keys.claim(
                userId, idempotencyKey, requestHash, attemptToken,
                now, now.plus(STALE_AFTER), now.plus(EXPIRES_AFTER)));

        return switch (claim) {
            case ClaimResult.Completed(Booking booking) -> new IdempotentBookingResult(booking, true);
            case ClaimResult.InProgress() -> throw new IdempotencyInProgressException();
            case ClaimResult.PayloadMismatch() -> throw new IdempotencyKeyMismatchException();
            case ClaimResult.Claimed() -> new IdempotentBookingResult(
                    createAndComplete(userId, idempotencyKey, attemptToken,
                            tripId, passengerName, passengerPhone, quantity),
                    false);
        };
    }

    private Booking createAndComplete(Long userId, String idempotencyKey, UUID attemptToken, Long tripId,
                                      String passengerName, String passengerPhone, int quantity) {
        try {
            return transactionRunner.inTransaction(() -> {
                Booking booking = bookingService.create(userId, tripId, passengerName, passengerPhone, quantity);
                if (!keys.complete(userId, idempotencyKey, attemptToken, booking)) {
                    throw new IdempotencyInProgressException();
                }
                return booking;
            });
        } catch (RuntimeException e) {
            try {
                transactionRunner.inNewTransaction(() -> {
                    keys.release(userId, idempotencyKey, attemptToken);
                    return null;
                });
            } catch (RuntimeException releaseError) {
                e.addSuppressed(releaseError);
            }
            throw e;
        }
    }
}
