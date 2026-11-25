package com.example.busbooking.business.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class BookingRequestHasher {

    private BookingRequestHasher() {
    }

    public static String hash(Long tripId, String passengerName, String passengerPhone, int quantity) {
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(passengerName, "passengerName");
        Objects.requireNonNull(passengerPhone, "passengerPhone");

        String canonical = "tripId=" + tripId + "\n"
                + "quantity=" + quantity + "\n"
                + "passengerName=" + passengerName.length() + ":" + passengerName + "\n"
                + "passengerPhone=" + passengerPhone.length() + ":" + passengerPhone;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
