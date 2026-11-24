package com.example.busbooking.data;

import com.example.busbooking.business.port.repository.BookingIdempotencyRepository;
import com.example.busbooking.business.port.repository.BookingIdempotencyRepository.ClaimResult;
import com.example.busbooking.domain.enums.BookingStatus;
import com.example.busbooking.domain.model.Booking;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BookingIdempotencyRepositoryAdapterTest {
    private static final String KEY = "key-1";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Duration STALE = Duration.ofSeconds(60);
    private static final Duration TTL = Duration.ofHours(24);

    @Autowired private BookingIdempotencyRepository keys;
    @Autowired private JdbcTemplate jdbc;

    private long userId;
    private long bookingId;

    @BeforeEach
    void createFixtures() {
        cleanFixtures();
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'USER'", Long.class);
        userId = jdbc.queryForObject("""
                INSERT INTO users(full_name, email, password, phone, role_id, email_verified)
                VALUES ('Idem User', 'idem.user@example.com', 'hash', '0912345678', ?, TRUE) RETURNING id
                """, Long.class, roleId);
        long operatorId = jdbc.queryForObject("""
                INSERT INTO bus_operators(name, address, phone, email, status)
                VALUES ('Idem Operator', 'Ha Noi', '0912345678', 'idem.operator@example.com', 'ACTIVE') RETURNING id
                """, Long.class);
        long vehicleId = jdbc.queryForObject("""
                INSERT INTO vehicles(operator_id, license_plate, vehicle_type, seat_capacity, status)
                VALUES (?, 'IDEM-001', 'Giuong nam', 40, 'ACTIVE') RETURNING id
                """, Long.class, operatorId);
        long origin = jdbc.queryForObject("""
                INSERT INTO locations(name, province, address) VALUES ('Idem Ben A', 'Ha Noi', 'So 1') RETURNING id
                """, Long.class);
        long destination = jdbc.queryForObject("""
                INSERT INTO locations(name, province, address) VALUES ('Idem Ben B', 'Hai Phong', 'So 2') RETURNING id
                """, Long.class);
        long routeId = jdbc.queryForObject("""
                INSERT INTO routes(origin_id, destination_id, estimated_duration_minutes, status)
                VALUES (?, ?, 120, 'ACTIVE') RETURNING id
                """, Long.class, origin, destination);
        long tripId = jdbc.queryForObject("""
                INSERT INTO trips(route_id, vehicle_id, departure_time, arrival_time, price, available_seats, status)
                VALUES (?, ?, now() + interval '1 day', now() + interval '1 day 2 hours', 250000, 38, 'SCHEDULED')
                RETURNING id
                """, Long.class, routeId, vehicleId);
        bookingId = jdbc.queryForObject("""
                INSERT INTO bookings(booking_code, user_id, trip_id, passenger_name, passenger_phone,
                                     quantity, unit_price, total_amount, status, booked_at)
                VALUES ('IDEM-BK-1', ?, ?, 'Khanh', '0912345678', 2, 250000, 500000, 'CONFIRMED', now())
                RETURNING id
                """, Long.class, userId, tripId);
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM booking_idempotency_keys WHERE user_id IN (SELECT id FROM users WHERE email = 'idem.user@example.com')");
        jdbc.update("DELETE FROM bookings WHERE booking_code = 'IDEM-BK-1'");
        jdbc.update("DELETE FROM trips WHERE vehicle_id IN (SELECT id FROM vehicles WHERE license_plate = 'IDEM-001')");
        jdbc.update("DELETE FROM routes WHERE origin_id IN (SELECT id FROM locations WHERE name LIKE 'Idem Ben %')");
        jdbc.update("DELETE FROM locations WHERE name LIKE 'Idem Ben %'");
        jdbc.update("DELETE FROM vehicles WHERE license_plate = 'IDEM-001'");
        jdbc.update("DELETE FROM bus_operators WHERE email = 'idem.operator@example.com'");
        jdbc.update("DELETE FROM users WHERE email = 'idem.user@example.com'");
    }

    @Test
    void firstClaimWinsAndSecondSeesInProgress() {
        assertThat(claim(HASH_A, UUID.randomUUID(), NOW)).isInstanceOf(ClaimResult.Claimed.class);
        assertThat(claim(HASH_A, UUID.randomUUID(), NOW.plusSeconds(1))).isInstanceOf(ClaimResult.InProgress.class);
    }

    @Test
    void differentBodyWithSameKeyIsMismatch() {
        claim(HASH_A, UUID.randomUUID(), NOW);
        assertThat(claim(HASH_B, UUID.randomUUID(), NOW.plusSeconds(1))).isInstanceOf(ClaimResult.PayloadMismatch.class);
    }

    @Test
    void completedKeyReplaysTheSameBookingSnapshot() {
        UUID token = UUID.randomUUID();
        claim(HASH_A, token, NOW);
        Booking booking = booking();
        assertThat(keys.complete(userId, KEY, token, booking)).isTrue();

        ClaimResult replay = claim(HASH_A, UUID.randomUUID(), NOW.plusSeconds(5));

        assertThat(replay).isInstanceOf(ClaimResult.Completed.class);
        Booking replayed = ((ClaimResult.Completed) replay).booking();
        assertThat(replayed).usingRecursiveComparison().isEqualTo(booking);
    }

    @Test
    void staleAttemptLosesOwnershipAfterReclaim() {
        UUID oldToken = UUID.randomUUID();
        UUID newToken = UUID.randomUUID();
        claim(HASH_A, oldToken, NOW);

        assertThat(claim(HASH_A, newToken, NOW.plus(STALE))).isInstanceOf(ClaimResult.Claimed.class);

        assertThat(keys.complete(userId, KEY, oldToken, booking())).isFalse();
        assertThat(keys.complete(userId, KEY, newToken, booking())).isTrue();
    }

    @Test
    void releaseOnlyDeletesOwnAttempt() {
        UUID token = UUID.randomUUID();
        claim(HASH_A, token, NOW);

        keys.release(userId, KEY, UUID.randomUUID());
        assertThat(countKeys()).isEqualTo(1);

        keys.release(userId, KEY, token);
        assertThat(countKeys()).isZero();
    }

    @Test
    void expiredKeyIsTreatedAsNew() {
        UUID token = UUID.randomUUID();
        claim(HASH_A, token, NOW);
        keys.complete(userId, KEY, token, booking());

        assertThat(claim(HASH_B, UUID.randomUUID(), NOW.plus(TTL))).isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    void deleteExpiredRemovesOnlyExpiredKeys() {
        claim(HASH_A, UUID.randomUUID(), NOW);

        assertThat(keys.deleteExpired(NOW.plus(TTL).minusSeconds(1))).isZero();
        assertThat(keys.deleteExpired(NOW.plus(TTL))).isEqualTo(1);
    }

    private ClaimResult claim(String hash, UUID token, Instant now) {
        return keys.claim(userId, KEY, hash, token, now, now.plus(STALE), now.plus(TTL));
    }

    private Booking booking() {
        return new Booking(bookingId, "IDEM-BK-1", userId, 1L, "Khanh", "0912345678", 2,
                new BigDecimal("250000.00"), new BigDecimal("500000.00"),
                BookingStatus.CONFIRMED, LocalDateTime.of(2026, 11, 24, 17, 0, 0, 123456789));
    }

    private Integer countKeys() {
        return jdbc.queryForObject("SELECT count(*) FROM booking_idempotency_keys WHERE user_id = ?", Integer.class, userId);
    }
}