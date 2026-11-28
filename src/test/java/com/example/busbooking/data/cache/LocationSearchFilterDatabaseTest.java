package com.example.busbooking.data.cache;

import com.example.busbooking.business.port.repository.LocationRepository;
import com.example.busbooking.domain.model.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class LocationSearchFilterDatabaseTest {

    @Autowired private LocationRepository locations;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void insertFixtures() {
        deleteFixtures();
        insert("Bến xe Mỹ Đình", "Hà Nội", "LSF 1 Phạm Hùng");
        insert("Bến xe Giáp Bát", "HÀ NỘI", "LSF 2 Giải Phóng");
        insert("Bến xe Trung tâm", "Đà Nẵng", "LSF 3 Tôn Đức Thắng");
        insert("Bến xe Trung tâm", "Đà Nẵng", "LSF 4 Điện Biên Phủ");
        insert("Bến xe Đức Long", "Gia Lai", "LSF 5 Trường Chinh");
        insert("Ben xe Ha Noi", "Ha Noi", "LSF 6 Khong dau");
        insert("Bến xe An Sương", "Hồ Chí Minh", "LSF 7 Quốc lộ 22");
        insert("İstanbul Otogar", "Zeta", "LSF 8 Bayrampaşa");
        insert("ΟΔΟΣ", "Zeta", "LSF 9 Greek");
    }

    @AfterEach
    void deleteFixtures() {
        jdbc.update("DELETE FROM locations WHERE address LIKE 'LSF %'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "đà", "ĐÀ NẴNG", "nẵng", "đ", "ha noi", "hà nội", "HÀ", "bến xe",
            "lsf", "istanbul", "i", "οδοσ", "ΟΔΟΣ", "quốc lộ 22", "a", "zzz"})
    void inMemoryFilterMatchesSqlExactly(String query) {
        List<Location> all = locations.search("");

        assertThat(ids(LocationSearchFilter.filter(all, query))).isEqualTo(ids(locations.search(query)));
    }

    @Test
    void fixturesAreActuallyMatched() {
        List<Location> all = locations.search("");

        assertThat(fixtures(LocationSearchFilter.filter(all, "ĐÀ NẴNG"))).hasSize(2);
        assertThat(fixtures(LocationSearchFilter.filter(all, "istanbul"))).hasSize(1);
        assertThat(fixtures(LocationSearchFilter.filter(all, "οδοσ"))).hasSize(1);
    }

    @Test
    void javaSortWouldNotMatchDatabaseCollation() {
        List<Location> databaseOrder = locations.search("");
        List<Location> javaOrder = databaseOrder.stream()
                .sorted(Comparator.comparing((Location location) -> LocationSearchFilter.lower(location.province()))
                        .thenComparing(location -> LocationSearchFilter.lower(location.name()))
                        .thenComparing(Location::id))
                .toList();

        assertThat(ids(javaOrder)).isNotEqualTo(ids(databaseOrder));
    }

    @Test
    void emptyQueryReturnsDatabaseOrderUnchanged() {
        List<Location> all = locations.search("");

        assertThat(LocationSearchFilter.filter(all, "")).isSameAs(all);
    }

    private void insert(String name, String province, String address) {
        jdbc.update("INSERT INTO locations(name, province, address) VALUES (?, ?, ?)", name, province, address);
    }

    private static List<Location> fixtures(List<Location> list) {
        return list.stream().filter(location -> location.address().startsWith("LSF ")).toList();
    }

    private static List<Long> ids(List<Location> list) {
        return list.stream().map(Location::id).toList();
    }
}
