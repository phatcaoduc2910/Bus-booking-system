package com.example.busbooking.domain.exception;

public class IdempotencyInProgressException extends BusinessException {
    public IdempotencyInProgressException() {
        super("A request with this Idempotency-Key is still being processed");
    }
}
