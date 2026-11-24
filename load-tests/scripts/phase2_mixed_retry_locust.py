"""Phase 2 Scenario 3: Mixed Workload + Sequential 100ms Retry (Idempotency-Key).

Traffic mix:
  - 50% GET /api/trips (Zipf 80/20)
  - 20% GET /api/users/me/bookings (authenticated user history)
  - 30% POST /api/bookings with unique Idempotency-Key:
      * Attempt 1 runs synchronously and waits for HTTP 201.
      * With 20% probability after Attempt 1 returns 201, sleeps 100ms (gevent.sleep(0.1))
        and resends the exact same Idempotency-Key + body (simulating response packet loss).
      * Before (Phase 1): server ignores Idempotency-Key -> creates duplicate booking (~20% extra).
      * After  (Phase 2): server hits COMPLETED snapshot -> returns 201 + Idempotent-Replayed: true,
        Error Rate = 0%, and 0 duplicate bookings in PostgreSQL.

Note: Concurrent duplicate IN_PROGRESS -> 409 is verified separately via JUnit CountDownLatch
integration tests so it does not distort Locust HTTP error rate comparisons.
"""

from __future__ import annotations

import itertools
import json
import os
import random
import sys
import uuid
from pathlib import Path

import gevent
from locust import HttpUser, between, events, task

sys.path.insert(0, str(Path(__file__).resolve().parent))
from phase2_common import (  # noqa: E402
    HOT_ROUTES,
    LOCATION_IDS,
    MIXED_BOOKING_MAX_TRIP_ID,
    date_offset_iso,
    get_base_date,
    load_user_tokens,
    wait_bounds,
)

TOKENS = load_user_tokens()
USER_SLOT = itertools.count(0)
BASE_DATE = get_base_date()
HOT_DATES = [date_offset_iso(BASE_DATE, d) for d in range(1, 4)]
ALL_DATES = [date_offset_iso(BASE_DATE, d) for d in range(1, 31)]
HOT_RATIO = float(os.environ.get("BUSBOOKING_HOT_RATIO", "0.80"))
RETRY_PROB = float(os.environ.get("BUSBOOKING_RETRY_PROB", "0.20"))
RETRY_DELAY_SEC = float(os.environ.get("BUSBOOKING_RETRY_DELAY_SEC", "0.10"))
SUMMARY_PATH = os.environ.get("BUSBOOKING_RETRY_SUMMARY_PATH", "").strip()
WAIT_MIN, WAIT_MAX = wait_bounds(0.15, 0.35)

STATS = {
    "logical_bookings_created": 0,
    "retries_sent": 0,
    "retries_201": 0,
    "retries_replayed_header": 0,
    "rejected_409_insufficient_seats": 0,
    "unexpected_errors": 0,
}


class MixedRetryUser(HttpUser):
    wait_time = between(WAIT_MIN, WAIT_MAX)

    def on_start(self) -> None:
        idx = next(USER_SLOT)
        self.user_idx = idx
        self.token = TOKENS[idx % len(TOKENS)]

    @task(5)
    def search_trips(self) -> None:
        if random.random() < HOT_RATIO:
            origin_id, dest_id = random.choice(HOT_ROUTES)
            dep_date = random.choice(HOT_DATES)
        else:
            origin_id, dest_id = random.sample(LOCATION_IDS, 2)
            dep_date = random.choice(ALL_DATES)

        with self.client.get(
            "/api/trips",
            params={
                "originId": origin_id,
                "destinationId": dest_id,
                "departureDate": dep_date,
            },
            name="GET /api/trips",
            catch_response=True,
            timeout=15,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(2)
    def booking_history(self) -> None:
        with self.client.get(
            "/api/users/me/bookings",
            headers={"Authorization": f"Bearer {self.token}"},
            name="GET /api/users/me/bookings",
            catch_response=True,
            timeout=15,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(3)
    def create_booking_and_optional_retry(self) -> None:
        trip_id = random.randint(1, MIXED_BOOKING_MAX_TRIP_ID)
        idem_key = str(uuid.uuid4())
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Idempotency-Key": idem_key,
        }
        payload = {
            "tripId": trip_id,
            "passengerName": f"Mixed User {self.user_idx + 1}",
            "passengerPhone": f"093{(self.user_idx + 1) % 10000000:07d}",
            "quantity": 1,
        }

        first_created = False
        with self.client.post(
            "/api/bookings",
            json=payload,
            headers=headers,
            name="POST /api/bookings [initial]",
            catch_response=True,
            timeout=15,
        ) as resp:
            if resp.status_code == 201:
                STATS["logical_bookings_created"] += 1
                first_created = True
                resp.success()
            elif resp.status_code == 409 and _is_insufficient_seats(resp):
                STATS["rejected_409_insufficient_seats"] += 1
                resp.success()
            else:
                STATS["unexpected_errors"] += 1
                resp.failure(f"HTTP {resp.status_code}: {resp.text[:160]}")

        # Sequential retry: only after Attempt 1 committed (201), wait 100ms then resend.
        if first_created and random.random() < RETRY_PROB:
            gevent.sleep(RETRY_DELAY_SEC)
            STATS["retries_sent"] += 1
            with self.client.post(
                "/api/bookings",
                json=payload,
                headers=headers,
                name="POST /api/bookings [retry-100ms]",
                catch_response=True,
                timeout=15,
            ) as retry_resp:
                if retry_resp.status_code == 201:
                    STATS["retries_201"] += 1
                    if retry_resp.headers.get("Idempotent-Replayed", "").lower() == "true":
                        STATS["retries_replayed_header"] += 1
                    retry_resp.success()
                else:
                    STATS["unexpected_errors"] += 1
                    retry_resp.failure(f"Retry HTTP {retry_resp.status_code}: {retry_resp.text[:160]}")


def _is_insufficient_seats(resp) -> bool:
    try:
        body = resp.json()
        return body.get("error") == "INSUFFICIENT_SEATS"
    except Exception:
        return False


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs) -> None:
    print(f"[MIXED_RETRY_SUMMARY] {json.dumps(STATS)}")
    if SUMMARY_PATH:
        Path(SUMMARY_PATH).parent.mkdir(parents=True, exist_ok=True)
        Path(SUMMARY_PATH).write_text(json.dumps(STATS, indent=2), encoding="utf-8")
