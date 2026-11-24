"""Shared helpers and deterministic dataset constants for BusBooking Phase 2 Locust tests."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
from datetime import date, timedelta
from typing import List, Tuple

# Matches routes 1..20 in load-tests/datasets/phase2_seed_10k.sql (origin_id, destination_id).
HOT_ROUTES: List[Tuple[int, int]] = [
    (1, 7),    # Ha Noi (My Dinh) -> Hai Phong
    (7, 1),    # Hai Phong -> Ha Noi
    (1, 6),    # Ha Noi -> Da Nang
    (6, 1),    # Da Nang -> Ha Noi
    (1, 13),   # Ha Noi -> Vinh
    (13, 1),   # Vinh -> Ha Noi
    (1, 14),   # Ha Noi -> Thanh Hoa
    (1, 15),   # Ha Noi -> Ha Long
    (1, 23),   # Ha Noi -> Sa Pa
    (2, 16),   # Ha Noi (Giap Bat) -> Ninh Binh
    (4, 11),   # Ho Chi Minh -> Da Lat
    (11, 4),   # Da Lat -> Ho Chi Minh
    (4, 10),   # Ho Chi Minh -> Nha Trang
    (10, 4),   # Nha Trang -> Ho Chi Minh
    (5, 9),    # Ho Chi Minh (Mien Tay) -> Can Tho
    (9, 5),    # Can Tho -> Ho Chi Minh
    (4, 38),   # Ho Chi Minh -> Vung Tau
    (4, 37),   # Ho Chi Minh -> Phan Thiet
    (6, 12),   # Da Nang -> Hue
    (6, 34),   # Da Nang -> Quy Nhon
]

LOCATION_IDS: List[int] = list(range(1, 51))

LOCATION_QUERIES: List[str] = [
    "",
    "ha noi",
    "ho chi minh",
    "da nang",
    "hai phong",
    "nha trang",
    "da lat",
    "can tho",
    "ben xe",
    "vinh",
    "hue",
]

CONTENTION_TRIP_ID: int = int(os.environ.get("BUSBOOKING_CONTENTION_TRIP_ID", "10000"))
MIXED_BOOKING_MAX_TRIP_ID: int = int(os.environ.get("BUSBOOKING_MIXED_MAX_TRIP_ID", "9999"))
SEEDED_HISTORICAL_BOOKINGS: int = int(os.environ.get("BUSBOOKING_SEEDED_BOOKINGS", "5000"))


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def mint_jwt(
    user_id: int,
    secret_b64: str,
    issuer: str = "busbooking",
    ttl_seconds: int = 86400,
) -> str:
    """Mint an HS256 JWT matching com.example.busbooking.security.JwtService."""
    secret_bytes = base64.b64decode(secret_b64)
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": str(user_id),
        "iss": issuer,
        "iat": now - 5,  # Slight backdate avoids clock skew failure at exact boundary.
        "exp": now + ttl_seconds,
    }
    signing_input = (
        f"{_b64url(json.dumps(header, separators=(',', ':')).encode('utf-8'))}."
        f"{_b64url(json.dumps(payload, separators=(',', ':')).encode('utf-8'))}"
    )
    signature = hmac.new(secret_bytes, signing_input.encode("ascii"), hashlib.sha256).digest()
    return f"{signing_input}.{_b64url(signature)}"


def load_user_tokens(default_count: int = 1000) -> List[str]:
    """Return JWTs for seeded users 1..N using BUSBOOKING_JWT_SECRET, or fallback to BUSBOOKING_TEST_JWT."""
    secret_b64 = os.environ.get("BUSBOOKING_JWT_SECRET", "").strip()
    if secret_b64:
        issuer = os.environ.get("BUSBOOKING_JWT_ISSUER", "busbooking").strip() or "busbooking"
        count = int(os.environ.get("BUSBOOKING_USER_COUNT", str(default_count)))
        ttl = int(os.environ.get("BUSBOOKING_JWT_TTL_SECONDS", "86400"))
        return [mint_jwt(uid, secret_b64, issuer=issuer, ttl_seconds=ttl) for uid in range(1, count + 1)]

    single_token = os.environ.get("BUSBOOKING_TEST_JWT", "").strip()
    if single_token:
        return [single_token]

    raise RuntimeError(
        "Set BUSBOOKING_JWT_SECRET (recommended, mints 1,000 user JWTs) or BUSBOOKING_TEST_JWT."
    )


def get_base_date() -> date:
    """Return the benchmark base date matching PostgreSQL CURRENT_DATE when seeded."""
    raw = os.environ.get("BUSBOOKING_BASE_DATE", "").strip()
    return date.fromisoformat(raw) if raw else date.today()


def date_offset_iso(base: date, days: int) -> str:
    return (base + timedelta(days=days)).isoformat()


def wait_bounds(default_min: float = 0.1, default_max: float = 0.3) -> Tuple[float, float]:
    wmin = float(os.environ.get("BUSBOOKING_WAIT_MIN", str(default_min)))
    wmax = float(os.environ.get("BUSBOOKING_WAIT_MAX", str(default_max)))
    return wmin, wmax
