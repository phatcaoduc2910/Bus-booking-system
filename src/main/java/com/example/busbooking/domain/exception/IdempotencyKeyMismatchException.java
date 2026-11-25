package com.example.busbooking.domain.exception;

public class IdempotencyKeyMismatchException extends BusinessException {
    public IdempotencyKeyMismatchException() {
        super("Idempotency-Key was already used with a different request payload");
    }
}
