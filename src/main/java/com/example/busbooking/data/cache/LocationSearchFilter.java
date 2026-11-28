package com.example.busbooking.data.cache;

import com.example.busbooking.domain.model.Location;

import java.util.List;

public final class LocationSearchFilter {

    private LocationSearchFilter() {
    }

    public static List<Location> filter(List<Location> allInDatabaseOrder, String query) {
        if (query == null || query.isEmpty()) {
            return allInDatabaseOrder;
        }
        String needle = lower(query);
        return allInDatabaseOrder.stream()
                .filter(location -> lower(location.name()).contains(needle)
                        || lower(location.province()).contains(needle)
                        || lower(location.address()).contains(needle))
                .toList();
    }

    static String lower(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().map(Character::toLowerCase).forEach(result::appendCodePoint);
        return result.toString();
    }
}
