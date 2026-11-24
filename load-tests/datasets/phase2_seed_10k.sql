-- =============================================================================
-- BusBooking Phase 2 Large Benchmark Dataset Seed (Deterministic, < 2s)
-- =============================================================================
-- Dataset size:
--   - 10 bus operators       (id: 1..10)
--   - 50 locations           (id: 1..50)
--   - 200 routes             (id: 1..200; routes 1..20 are hot corridors)
--   - 100 vehicles           (id: 1..100; seat_capacity = 40 each)
--   - 10,000 trips           (id: 1..10000; SCHEDULED across next 30 days)
--       * Trips 1..1500     : 20 hot routes x 3 next days x 25 trips/day
--                             (triggers pagination X-Has-Next=true at size=20)
--       * Trips 1501..9999  : 8,499 trips across all 200 routes x 30 days
--       * Trip 10000        : Dedicated Last-Seat Contention trip (40/40 free)
--   - 1,000 verified USERs   (loadtest_user_0001@example.com .. 1000@example.com)
--   - 1 verified ADMIN       (loadtest_admin@example.com)
--       * Password for all loadtest accounts: LoadTest@123
--   - 5,000 CONFIRMED historical bookings (5 per user) for EXPLAIN ANALYZE on
--     GET /api/users/me/bookings, with available_seats kept 100% balanced.
--
-- Compatible with both Phase 1 (V1..V16) and Phase 2 (V1..V18).
-- Usage:
--   psql -v ON_ERROR_STOP=1 -h localhost -p 5435 -U busbooking_user -d busbooking_dev_fresh -f load-tests/datasets/phase2_seed_10k.sql
-- =============================================================================

BEGIN;

SET LOCAL synchronous_commit = off;

-- 1. Clean existing domain data while preserving Flyway history and roles table.
TRUNCATE TABLE
    bookings,
    email_verifications,
    trips,
    routes,
    vehicles,
    bus_operators,
    locations,
    users
RESTART IDENTITY CASCADE;

-- Clean Phase 2 idempotency table if V18 has already been applied.
DO $$
BEGIN
    IF to_regclass('public.booking_idempotency_keys') IS NOT NULL THEN
        EXECUTE 'TRUNCATE TABLE public.booking_idempotency_keys RESTART IDENTITY CASCADE';
    END IF;
END $$;

-- 2. Bus Operators (10 active operators)
INSERT INTO bus_operators (id, name, address, phone, email, status, created_at)
SELECT
    g,
    'Nha Xe Tuyen ' || lpad(g::text, 2, '0'),
    'So ' || (g * 10) || ' Duong Quoc Lo 1A, Viet Nam',
    '09010000' || lpad(g::text, 2, '0'),
    'operator' || lpad(g::text, 2, '0') || '@busbooking.example',
    'ACTIVE',
    CURRENT_TIMESTAMP - (g || ' days')::interval
FROM generate_series(1, 10) AS g;

-- 3. Locations (50 normalized bus stations across Vietnam)
INSERT INTO locations (id, name, province, address)
VALUES
    ( 1, 'Ben xe My Dinh',          'Ha Noi',          '20 Pham Hung, Nam Tu Liem, Ha Noi'),
    ( 2, 'Ben xe Giap Bat',         'Ha Noi',          'Giai Phong, Hoang Mai, Ha Noi'),
    ( 3, 'Ben xe Nuoc Ngam',        'Ha Noi',          '01 Ngoc Hoi, Hoang Mai, Ha Noi'),
    ( 4, 'Ben xe Mien Dong Moi',    'Ho Chi Minh',     '501 Hoang Huu Nam, Thu Duc, Ho Chi Minh'),
    ( 5, 'Ben xe Mien Tay',         'Ho Chi Minh',     '395 Kinh Duong Vuong, Binh Tan, Ho Chi Minh'),
    ( 6, 'Ben xe Trung Tam Da Nang','Da Nang',         'Ton Duc Thang, Lien Chieu, Da Nang'),
    ( 7, 'Ben xe Niem Nghia',       'Hai Phong',       '275 Tran Nguyen Han, Le Chan, Hai Phong'),
    ( 8, 'Ben xe Thuong Ly',        'Hai Phong',       '52 Ha Noi, Hong Bang, Hai Phong'),
    ( 9, 'Ben xe Trung Tam Can Tho','Can Tho',         'Quoc Lo 1A, Cai Rang, Can Tho'),
    (10, 'Ben xe Phia Nam Nha Trang','Khanh Hoa',      'Duong 23 Thang 10, Nha Trang, Khanh Hoa'),
    (11, 'Ben xe Lien Tinh Da Lat', 'Lam Dong',        '01 To Hien Thanh, Da Lat, Lam Dong'),
    (12, 'Ben xe Phia Nam Hue',     'Thua Thien Hue',  '97 An Duong Vuong, Hue, Thua Thien Hue'),
    (13, 'Ben xe Bac Vinh',         'Nghe An',         'Xom 1 Nghi Kim, Vinh, Nghe An'),
    (14, 'Ben xe Phia Bac Thanh Hoa','Thanh Hoa',      'Quoc Lo 1A, Thanh Hoa'),
    (15, 'Ben xe Bai Chay',         'Quang Ninh',      '279 Ha Long, Ha Long, Quang Ninh'),
    (16, 'Ben xe Ninh Binh',        'Ninh Binh',       '207 Le Dai Hanh, Ninh Binh'),
    (17, 'Ben xe Nam Dinh',         'Nam Dinh',        'Dien Bien, Nam Dinh'),
    (18, 'Ben xe Thai Binh',        'Thai Binh',       'Ly Bon, Thai Binh'),
    (19, 'Ben xe Hai Duong',        'Hai Duong',       'Hong Quang, Hai Duong'),
    (20, 'Ben xe Bac Ninh',         'Bac Ninh',        'Nguyen Du, Bac Ninh'),
    (21, 'Ben xe Thai Nguyen',      'Thai Nguyen',     'Duong Thong Nhat, Thai Nguyen'),
    (22, 'Ben xe Lao Cai',          'Lao Cai',         'Pho Moi, Lao Cai'),
    (23, 'Ben xe Sa Pa',            'Lao Cai',         'Dien Bien Phu, Sa Pa, Lao Cai'),
    (24, 'Ben xe Ha Giang',         'Ha Giang',        'Duong 19 Thang 5, Ha Giang'),
    (25, 'Ben xe Son La',           'Son La',          'Quoc Lo 6, Son La'),
    (26, 'Ben xe Dien Bien',        'Dien Bien',       'Vo Nguyen Giap, Dien Bien Phu, Dien Bien'),
    (27, 'Ben xe Hoa Binh',         'Hoa Binh',        'Cu Chinh Lan, Hoa Binh'),
    (28, 'Ben xe Viet Tri',         'Phu Tho',         'Hung Vuong, Viet Tri, Phu Tho'),
    (29, 'Ben xe Ha Tinh',          'Ha Tinh',         'Ham Nghi, Ha Tinh'),
    (30, 'Ben xe Dong Hoi',         'Quang Binh',      'Tran Hung Dao, Dong Hoi, Quang Binh'),
    (31, 'Ben xe Dong Ha',          'Quang Tri',       'Le Duan, Dong Ha, Quang Tri'),
    (32, 'Ben xe Tam Ky',           'Quang Nam',       'Phan Boi Chau, Tam Ky, Quang Nam'),
    (33, 'Ben xe Quang Ngai',       'Quang Ngai',      '02 Tran Khanh Du, Quang Ngai'),
    (34, 'Ben xe Quy Nhon',         'Binh Dinh',       '71 Tay Son, Quy Nhon, Binh Dinh'),
    (35, 'Ben xe Tuy Hoa',          'Phu Yen',         'Nguyen Tat Thanh, Tuy Hoa, Phu Yen'),
    (36, 'Ben xe Phan Rang',        'Ninh Thuan',      'Thong Nhat, Phan Rang, Ninh Thuan'),
    (37, 'Ben xe Phan Thiet',       'Binh Thuan',      '01 Tu Van Tu, Phan Thiet, Binh Thuan'),
    (38, 'Ben xe Vung Tau',         'Ba Ria Vung Tau', '192 Nam Ky Khoi Nghia, Vung Tau'),
    (39, 'Ben xe Bien Hoa',         'Dong Nai',        '04 Nguyen Ai Quoc, Bien Hoa, Dong Nai'),
    (40, 'Ben xe Binh Duong',       'Binh Duong',      'Duong 30 Thang 4, Thu Dau Mot, Binh Duong'),
    (41, 'Ben xe Buon Ma Thuot',    'Dak Lak',         '71 Nguyen Chi Thanh, Buon Ma Thuot, Dak Lak'),
    (42, 'Ben xe Duc Long Gia Lai', 'Gia Lai',         '43 Ly Nam De, Pleiku, Gia Lai'),
    (43, 'Ben xe Kon Tum',          'Kon Tum',         '281 Phan Dinh Phung, Kon Tum'),
    (44, 'Ben xe Long An',          'Long An',         'Quoc Lo 1A, Tan An, Long An'),
    (45, 'Ben xe Tien Giang',       'Tien Giang',      '42 Ap Bac, My Tho, Tien Giang'),
    (46, 'Ben xe Ben Tre',          'Ben Tre',         '303 Doan Hoang Minh, Ben Tre'),
    (47, 'Ben xe Vinh Long',        'Vinh Long',       '01E Dinh Tien Hoang, Vinh Long'),
    (48, 'Ben xe Long Xuyen',       'An Giang',        '392 Pham Cu Luong, Long Xuyen, An Giang'),
    (49, 'Ben xe Rach Gia',         'Kien Giang',      '260A Nguyen Binh Khiem, Rach Gia, Kien Giang'),
    (50, 'Ben xe Ca Mau',           'Ca Mau',          'Ly Thuong Kiet, Ca Mau');

-- 4. Vehicles (100 active vehicles, 40 seats each)
INSERT INTO vehicles (id, operator_id, license_plate, vehicle_type, seat_capacity, status, created_at)
SELECT
    g,
    ((g - 1) % 10) + 1,
    '29B' || lpad(g::text, 5, '0'),
    CASE (g % 3)
        WHEN 0 THEN 'LIMOUSINE'
        WHEN 1 THEN 'SLEEPER'
        ELSE 'SEATER'
    END,
    40,
    'ACTIVE',
    CURRENT_TIMESTAMP - (g || ' hours')::interval
FROM generate_series(1, 100) AS g;

-- 5. Routes (200 unique origin/destination pairs)
-- 5a. Routes 1..20: 20 high-demand corridors used by Read-heavy Zipf 80/20.
INSERT INTO routes (id, origin_id, destination_id, estimated_duration_minutes, status)
VALUES
    ( 1,  1,  7, 150, 'ACTIVE'),  -- Ha Noi (My Dinh) -> Hai Phong (Niem Nghia)
    ( 2,  7,  1, 150, 'ACTIVE'),  -- Hai Phong -> Ha Noi
    ( 3,  1,  6, 840, 'ACTIVE'),  -- Ha Noi -> Da Nang
    ( 4,  6,  1, 840, 'ACTIVE'),  -- Da Nang -> Ha Noi
    ( 5,  1, 13, 330, 'ACTIVE'),  -- Ha Noi -> Vinh
    ( 6, 13,  1, 330, 'ACTIVE'),  -- Vinh -> Ha Noi
    ( 7,  1, 14, 180, 'ACTIVE'),  -- Ha Noi -> Thanh Hoa
    ( 8,  1, 15, 180, 'ACTIVE'),  -- Ha Noi -> Ha Long (Quang Ninh)
    ( 9,  1, 23, 330, 'ACTIVE'),  -- Ha Noi -> Sa Pa
    (10,  2, 16, 120, 'ACTIVE'),  -- Ha Noi (Giap Bat) -> Ninh Binh
    (11,  4, 11, 420, 'ACTIVE'),  -- Ho Chi Minh -> Da Lat
    (12, 11,  4, 420, 'ACTIVE'),  -- Da Lat -> Ho Chi Minh
    (13,  4, 10, 480, 'ACTIVE'),  -- Ho Chi Minh -> Nha Trang
    (14, 10,  4, 480, 'ACTIVE'),  -- Nha Trang -> Ho Chi Minh
    (15,  5,  9, 210, 'ACTIVE'),  -- Ho Chi Minh (Mien Tay) -> Can Tho
    (16,  9,  5, 210, 'ACTIVE'),  -- Can Tho -> Ho Chi Minh
    (17,  4, 38, 150, 'ACTIVE'),  -- Ho Chi Minh -> Vung Tau
    (18,  4, 37, 210, 'ACTIVE'),  -- Ho Chi Minh -> Phan Thiet
    (19,  6, 12, 150, 'ACTIVE'),  -- Da Nang -> Hue
    (20,  6, 34, 360, 'ACTIVE');  -- Da Nang -> Quy Nhon

-- 5b. Routes 21..200: 180 deterministic long-tail routes spanning all 50 locations.
WITH candidate_pairs AS (
    SELECT
        o.id AS origin_id,
        d.id AS destination_id,
        120 + (((o.id * 37 + d.id * 17) % 10) * 60) AS duration_min,
        row_number() OVER (ORDER BY ((o.id * 73 + d.id * 29) % 2501), o.id, d.id) AS rn
    FROM locations o
    JOIN locations d ON o.id <> d.id
    WHERE NOT EXISTS (
        SELECT 1
        FROM routes r
        WHERE r.origin_id = o.id AND r.destination_id = d.id
    )
)
INSERT INTO routes (id, origin_id, destination_id, estimated_duration_minutes, status)
SELECT
    20 + rn,
    origin_id,
    destination_id,
    duration_min,
    'ACTIVE'
FROM candidate_pairs
WHERE rn <= 180;

-- 6. Trips (10,000 SCHEDULED trips across the next 30 days)
-- 6a. Trips 1..1500: 20 hot routes x 3 upcoming days x 25 trips/day = 1,500 trips.
--     Each hot (origin, destination, date) query has >= 25 rows so page size 20
--     always exercises pagination (X-Has-Next = true).
WITH hot_slots AS (
    SELECT
        row_number() OVER (ORDER BY day_offset, r.id, slot_idx) AS trip_id,
        r.id AS route_id,
        r.estimated_duration_minutes,
        day_offset,
        slot_idx
    FROM routes r
    CROSS JOIN generate_series(1, 3) AS day_offset
    CROSS JOIN generate_series(0, 24) AS slot_idx
    WHERE r.id <= 20
)
INSERT INTO trips (id, route_id, vehicle_id, departure_time, arrival_time, price, available_seats, status, created_at)
SELECT
    hs.trip_id,
    hs.route_id,
    ((hs.trip_id - 1) % 100) + 1,
    (CURRENT_DATE + (hs.day_offset || ' days')::interval + interval '5 hours' + ((hs.slot_idx * 45) || ' minutes')::interval)::timestamp,
    (CURRENT_DATE + (hs.day_offset || ' days')::interval + interval '5 hours' + ((hs.slot_idx * 45 + hs.estimated_duration_minutes) || ' minutes')::interval)::timestamp,
    (150000 + ((hs.route_id % 8) * 50000))::numeric(12, 2),
    40,
    'SCHEDULED',
    CURRENT_TIMESTAMP - interval '5 days'
FROM hot_slots hs;

-- 6b. Trips 1501..9999: 8,499 trips spread across all 200 routes x 30 upcoming days.
WITH general_slots AS (
    SELECT
        1500 + g AS trip_id,
        ((g - 1) % 200) + 1 AS route_id,
        (((g - 1) / 200) % 30) + 1 AS day_offset,
        ((g - 1) % 16) AS hour_slot
    FROM generate_series(1, 8499) AS g
)
INSERT INTO trips (id, route_id, vehicle_id, departure_time, arrival_time, price, available_seats, status, created_at)
SELECT
    gs.trip_id,
    gs.route_id,
    ((gs.trip_id - 1) % 100) + 1,
    (CURRENT_DATE + (gs.day_offset || ' days')::interval + ((6 + gs.hour_slot) || ' hours')::interval + (((gs.trip_id % 4) * 15) || ' minutes')::interval)::timestamp,
    (CURRENT_DATE + (gs.day_offset || ' days')::interval + ((6 + gs.hour_slot) || ' hours')::interval + (((gs.trip_id % 4) * 15 + r.estimated_duration_minutes) || ' minutes')::interval)::timestamp,
    (180000 + ((gs.route_id % 9) * 40000))::numeric(12, 2),
    40,
    'SCHEDULED',
    CURRENT_TIMESTAMP - interval '5 days'
FROM general_slots gs
JOIN routes r ON r.id = gs.route_id;

-- 6c. Trip 10000: Dedicated Last-Seat Contention target (40/40 seats available, 0 initial bookings).
INSERT INTO trips (id, route_id, vehicle_id, departure_time, arrival_time, price, available_seats, status, created_at)
SELECT
    10000,
    1,
    1,
    (CURRENT_DATE + interval '2 days 08:00')::timestamp,
    (CURRENT_DATE + interval '2 days 08:00' + (r.estimated_duration_minutes || ' minutes')::interval)::timestamp,
    250000.00,
    40,
    'SCHEDULED',
    CURRENT_TIMESTAMP - interval '1 day'
FROM routes r
WHERE r.id = 1;

-- 7. Users (1,000 verified USERs + 1 verified ADMIN)
--    BCrypt hash (strength 10) for password: LoadTest@123
INSERT INTO users (id, full_name, email, password, phone, role_id, status, email_verified, created_at, updated_at)
SELECT
    g,
    'Load Test User ' || lpad(g::text, 4, '0'),
    'loadtest_user_' || lpad(g::text, 4, '0') || '@example.com',
    '$2a$10$gp8Ngle1VmxLmPSk4alVju7g3bLMdrzyvjdyQgO04SwlMEJq6vZTG',
    '091' || lpad(g::text, 7, '0'),
    (SELECT id FROM roles WHERE name = 'USER'),
    'ACTIVE',
    TRUE,
    CURRENT_TIMESTAMP - interval '30 days',
    CURRENT_TIMESTAMP - interval '30 days'
FROM generate_series(1, 1000) AS g;

INSERT INTO users (id, full_name, email, password, phone, role_id, status, email_verified, created_at, updated_at)
VALUES (
    1001,
    'Load Test Admin',
    'loadtest_admin@example.com',
    '$2a$10$gp8Ngle1VmxLmPSk4alVju7g3bLMdrzyvjdyQgO04SwlMEJq6vZTG',
    '0909999999',
    (SELECT id FROM roles WHERE name = 'ADMIN'),
    'ACTIVE',
    TRUE,
    CURRENT_TIMESTAMP - interval '30 days',
    CURRENT_TIMESTAMP - interval '30 days'
);

-- 8. Historical Bookings (5,000 CONFIRMED rows: 5 per user across trips 1501..6500)
--    Gives realistic booking history for EXPLAIN ANALYZE on GET /api/users/me/bookings
--    without touching Trip 10000 (reserved for Last-Seat Contention).
INSERT INTO bookings (
    id, booking_code, user_id, trip_id, passenger_name, passenger_phone,
    quantity, unit_price, total_amount, status, booked_at
)
SELECT
    g,
    'BKSEED' || lpad(g::text, 6, '0'),
    ((g - 1) % 1000) + 1,
    1500 + g,
    'Passenger ' || lpad(g::text, 4, '0'),
    '098' || lpad((g % 10000000)::text, 7, '0'),
    1,
    t.price,
    t.price,
    'CONFIRMED',
    (CURRENT_TIMESTAMP - (((g * 7) % 43200) || ' minutes')::interval)::timestamp
FROM generate_series(1, 5000) AS g
JOIN trips t ON t.id = 1500 + g;

-- Synchronize available_seats so seat_capacity = available_seats + SUM(CONFIRMED quantity) on all 10,000 trips.
WITH seat_usage AS (
    SELECT
        t2.id AS trip_id,
        v.seat_capacity - COALESCE(b.booked_qty, 0) AS remaining_seats
    FROM trips t2
    JOIN vehicles v ON v.id = t2.vehicle_id
    LEFT JOIN (
        SELECT trip_id, SUM(quantity) AS booked_qty
        FROM bookings
        WHERE status = 'CONFIRMED'
        GROUP BY trip_id
    ) b ON b.trip_id = t2.id
)
UPDATE trips t
SET available_seats = su.remaining_seats
FROM seat_usage su
WHERE t.id = su.trip_id;

-- 9. Advance identity sequences so subsequent API inserts never collide with seeded IDs.
SELECT setval(pg_get_serial_sequence('public.bus_operators', 'id'), (SELECT MAX(id) FROM bus_operators), true);
SELECT setval(pg_get_serial_sequence('public.locations',     'id'), (SELECT MAX(id) FROM locations),     true);
SELECT setval(pg_get_serial_sequence('public.vehicles',      'id'), (SELECT MAX(id) FROM vehicles),      true);
SELECT setval(pg_get_serial_sequence('public.routes',        'id'), (SELECT MAX(id) FROM routes),        true);
SELECT setval(pg_get_serial_sequence('public.trips',         'id'), (SELECT MAX(id) FROM trips),         true);
SELECT setval(pg_get_serial_sequence('public.users',         'id'), (SELECT MAX(id) FROM users),         true);
SELECT setval(pg_get_serial_sequence('public.bookings',      'id'), (SELECT MAX(id) FROM bookings),      true);

COMMIT;

ANALYZE;
