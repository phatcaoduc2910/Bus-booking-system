"""Phase 2 Scenario 2: Last-Seat Contention (200 users competing for 40 seats).

Targets dedicated Trip id=10000 (40/40 seats free in phase2_seed_10k.sql).
Each virtual user uses a distinct verified user JWT and attempts to book 1 seat.

Status classification:
  - 201 Created                        -> success (seat acquired)
  - 409 Conflict (INSUFFICIENT_SEATS)  -> expected business rejection (counted separately, not an infra failure)
  - Anything else                      -> failure
"""

from __future__ import annotations

import itertools
import json
import os
import sys
import uuid
from pathlib import Path

from locust import HttpUser, constant, events, task
from locust.exception import StopUser

sys.path.insert(0, str(Path(__file__).resolve().parent))
from phase2_common import CONTENTION_TRIP_ID, load_user_tokens  # noqa: E402

TOKENS = load_user_tokens()
USER_SLOT = itertools.count(0)
ATTEMPTS_PER_USER = int(os.environ.get("BUSBOOKING_ATTEMPTS_PER_USER", "1"))
SUMMARY_PATH = os.environ.get("BUSBOOKING_CONTENTION_SUMMARY_PATH", "").strip()

STATS = {
    "trip_id": CONTENTION_TRIP_ID,
    "created_201": 0,
    "rejected_409_insufficient_seats": 0,
    "unexpected_errors": 0,
}


class LastSeatContentionUser(HttpUser):
    # Minimal wait so all spawned users contend on the same trip row simultaneously.
    wait_time = constant(0.01)

    def on_start(self) -> None:
        idx = next(USER_SLOT)
        self.user_idx = idx
        self.token = TOKENS[idx % len(TOKENS)]
        self.remaining = ATTEMPTS_PER_USER

    @task
    def compete_for_last_seats(self) -> None:
        if self.remaining <= 0:
            raise StopUser()
        self.remaining -= 1

        headers = {
            "Authorization": f"Bearer {self.token}",
            "Idempotency-Key": str(uuid.uuid4()),
        }
        payload = {
            "tripId": CONTENTION_TRIP_ID,
            "passengerName": f"Contention User {self.user_idx + 1}",
            "passengerPhone": f"092{(self.user_idx + 1) % 10000000:07d}",
            "quantity": 1,
        }

        with self.client.post(
            "/api/bookings",
            json=payload,
            headers=headers,
            name="POST /api/bookings [contention]",
            catch_response=True,
            timeout=30,
        ) as resp:
            if resp.status_code == 201:
                STATS["created_201"] += 1
                resp.success()
            elif resp.status_code == 409 and _is_insufficient_seats(resp):
                STATS["rejected_409_insufficient_seats"] += 1
                resp.success()
            else:
                STATS["unexpected_errors"] += 1
                resp.failure(f"HTTP {resp.status_code}: {resp.text[:160]}")


def _is_insufficient_seats(resp) -> bool:
    try:
        body = resp.json()
        return body.get("error") == "INSUFFICIENT_SEATS"
    except Exception:
        return False


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs) -> None:
    print(f"[CONTENTION_SUMMARY] {json.dumps(STATS)}")
    if SUMMARY_PATH:
        Path(SUMMARY_PATH).parent.mkdir(parents=True, exist_ok=True)
        Path(SUMMARY_PATH).write_text(json.dumps(STATS, indent=2), encoding="utf-8")
