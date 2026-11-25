package com.example.busbooking.business.result;

import com.example.busbooking.domain.model.Booking;

public record IdempotentBookingResult(Booking booking, boolean replayed) {
}
