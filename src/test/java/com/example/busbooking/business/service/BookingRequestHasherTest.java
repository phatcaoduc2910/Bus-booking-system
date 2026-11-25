package com.example.busbooking.business.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookingRequestHasherTest {

    @Test
    void producesKnownLowercaseSha256Hex() {
        String hash = BookingRequestHasher.hash(1L, "Nguyễn Văn A", "0901234567", 2);

        assertThat(hash).isEqualTo("27c4238a04980ddd8ff08b90aa4db5efca72c8a8f11898fca699a6344c1a21e2");
    }

    @Test
    void sameInputGivesSameHash() {
        assertThat(BookingRequestHasher.hash(5L, "An", "0900000000", 1))
                .isEqualTo(BookingRequestHasher.hash(5L, "An", "0900000000", 1));
    }

    @Test
    void anyFieldChangeGivesDifferentHash() {
        String base = BookingRequestHasher.hash(5L, "An", "0900000000", 1);

        assertThat(BookingRequestHasher.hash(6L, "An", "0900000000", 1)).isNotEqualTo(base);
        assertThat(BookingRequestHasher.hash(5L, "An ", "0900000000", 1)).isNotEqualTo(base);
        assertThat(BookingRequestHasher.hash(5L, "An", "0900000001", 1)).isNotEqualTo(base);
        assertThat(BookingRequestHasher.hash(5L, "An", "0900000000", 2)).isNotEqualTo(base);
    }

    @Test
    void lengthPrefixPreventsFieldBoundaryCollision() {
        assertThat(BookingRequestHasher.hash(1L, "ab", "c", 1))
                .isNotEqualTo(BookingRequestHasher.hash(1L, "a", "bc", 1));
    }
}
