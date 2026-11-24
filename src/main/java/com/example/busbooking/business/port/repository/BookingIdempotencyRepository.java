package com.example.busbooking.business.port.repository;

import com.example.busbooking.domain.model.Booking;

import java.time.Instant;
import java.util.UUID;

public interface BookingIdempotencyRepository {

    
    ClaimResult claim(long userId, String idempotencyKey, String requestHash, UUID attemptToken,
                      Instant now, Instant staleAt, Instant expiresAt);

    boolean complete(long userId, String idempotencyKey, UUID attemptToken, Booking booking);

    
    void release(long userId, String idempotencyKey, UUID attemptToken);

    int deleteExpired(Instant now);

    sealed interface ClaimResult {
        record Claimed() implements ClaimResult {}
        record InProgress() implements ClaimResult {}
        record PayloadMismatch() implements ClaimResult {}
        record Completed(Booking booking) implements ClaimResult {}
    }
}