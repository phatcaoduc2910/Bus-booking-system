package com.example.busbooking.data.adapter;

import com.example.busbooking.business.port.repository.BookingIdempotencyRepository;
import com.example.busbooking.domain.enums.BookingStatus;
import com.example.busbooking.domain.model.Booking;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class BookingIdempotencyRepositoryAdapter implements BookingIdempotencyRepository {
    private static final int MAX_CLAIM_ROUNDS = 3;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BookingIdempotencyRepositoryAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public ClaimResult claim(long userId, String idempotencyKey, String requestHash, UUID attemptToken,
                             Instant now, Instant staleAt, Instant expiresAt) {
        for (int round = 0; round < MAX_CLAIM_ROUNDS; round++) {
            int inserted = jdbc.update("""
                    INSERT INTO booking_idempotency_keys
                        (user_id, idempotency_key, request_hash, status, attempt_token,
                         created_at, stale_at, expires_at)
                    VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)
                    ON CONFLICT (user_id, idempotency_key) DO NOTHING
                    """, userId, idempotencyKey, requestHash, attemptToken,
                    Timestamp.from(now), Timestamp.from(staleAt), Timestamp.from(expiresAt));
            if (inserted == 1) {
                return new ClaimResult.Claimed();
            }

            List<StoredKey> rows = jdbc.query("""
                    SELECT request_hash, status, stale_at, expires_at, response_json
                    FROM booking_idempotency_keys
                    WHERE user_id = ? AND idempotency_key = ?
                    FOR UPDATE
                    """, (rs, i) -> new StoredKey(
                            rs.getString("request_hash"),
                            rs.getString("status"),
                            rs.getTimestamp("stale_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getString("response_json")),
                    userId, idempotencyKey);
            if (rows.isEmpty()) {
                continue; 
            }

            StoredKey stored = rows.getFirst();
            if (!now.isBefore(stored.expiresAt())) {
                jdbc.update("DELETE FROM booking_idempotency_keys WHERE user_id = ? AND idempotency_key = ?",
                        userId, idempotencyKey);
                continue;
            }
            if (!stored.requestHash().equals(requestHash)) {
                return new ClaimResult.PayloadMismatch();
            }
            if ("COMPLETED".equals(stored.status())) {
                return new ClaimResult.Completed(fromSnapshot(stored.responseJson()));
            }
            if (now.isBefore(stored.staleAt())) {
                return new ClaimResult.InProgress();
            }

            jdbc.update("""
                    UPDATE booking_idempotency_keys
                    SET attempt_token = ?, stale_at = ?, expires_at = ?
                    WHERE user_id = ? AND idempotency_key = ?
                    """, attemptToken, Timestamp.from(staleAt), Timestamp.from(expiresAt),
                    userId, idempotencyKey);
            return new ClaimResult.Claimed();
        }
        throw new IllegalStateException("Could not claim idempotency key after " + MAX_CLAIM_ROUNDS + " rounds");
    }

    @Override
    @Transactional
    public boolean complete(long userId, String idempotencyKey, UUID attemptToken, Booking booking) {
        int updated = jdbc.update("""
                UPDATE booking_idempotency_keys
                SET status = 'COMPLETED', booking_id = ?, response_json = ?
                WHERE user_id = ? AND idempotency_key = ?
                  AND attempt_token = ? AND status = 'IN_PROGRESS'
                """, booking.getId(), toSnapshot(booking), userId, idempotencyKey, attemptToken);
        return updated == 1;
    }

    @Override
    @Transactional
    public void release(long userId, String idempotencyKey, UUID attemptToken) {
        jdbc.update("""
                DELETE FROM booking_idempotency_keys
                WHERE user_id = ? AND idempotency_key = ?
                  AND attempt_token = ? AND status = 'IN_PROGRESS'
                """, userId, idempotencyKey, attemptToken);
    }

    @Override
    @Transactional
    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM booking_idempotency_keys WHERE expires_at <= ?", Timestamp.from(now));
    }

    private String toSnapshot(Booking b) {
        return json.writeValueAsString(new BookingSnapshot(
                b.getId(), b.getBookingCode(), b.getUserId(), b.getTripId(),
                b.getPassengerName(), b.getPassengerPhone(), b.getQuantity(),
                b.getUnitPrice(), b.getTotalAmount(), b.getStatus(), b.getBookedAt()));
    }

    private Booking fromSnapshot(String value) {
        BookingSnapshot s = json.readValue(value, BookingSnapshot.class);
        return new Booking(s.id(), s.bookingCode(), s.userId(), s.tripId(),
                s.passengerName(), s.passengerPhone(), s.quantity(),
                s.unitPrice(), s.totalAmount(), s.status(), s.bookedAt());
    }

    private record StoredKey(String requestHash, String status, Instant staleAt,
                             Instant expiresAt, String responseJson) {
    }

    record BookingSnapshot(Long id, String bookingCode, Long userId, Long tripId,
                           String passengerName, String passengerPhone, int quantity,
                           BigDecimal unitPrice, BigDecimal totalAmount,
                           BookingStatus status, LocalDateTime bookedAt) {
    }
}