"""Phase 2 Scenario 1: Read-heavy Zipf/Pareto 80/20 (100-200 users).

Traffic distribution:
  - 70% GET /api/trips?originId=...&destinationId=...&departureDate=...
      * 80% hot queries : 20 popular corridors x 3 nearest days (60 hot keys)
      * 20% cold queries: random origin != destination across 50 locations x 30 days
  - 20% GET /api/locations?q=...
  - 10% GET /api/trips/{id}

Used for both Before vs After comparison and the mandatory 4-step Ablation Study
on the 10,000-trip dataset (pagination -> V17 index -> Redis cache).
"""

from __future__ import annotations

import os
import random
import sys
from pathlib import Path

from locust import HttpUser, between, task

sys.path.insert(0, str(Path(__file__).resolve().parent))
from phase2_common import (  # noqa: E402
    HOT_ROUTES,
    LOCATION_IDS,
    LOCATION_QUERIES,
    date_offset_iso,
    get_base_date,
    wait_bounds,
)

BASE_DATE = get_base_date()
HOT_DATES = [date_offset_iso(BASE_DATE, d) for d in range(1, 4)]      # +1..+3 days
ALL_DATES = [date_offset_iso(BASE_DATE, d) for d in range(1, 31)]     # +1..+30 days
HOT_RATIO = float(os.environ.get("BUSBOOKING_HOT_RATIO", "0.80"))
WAIT_MIN, WAIT_MAX = wait_bounds(0.1, 0.3)


class ReadHeavyZipfUser(HttpUser):
    wait_time = between(WAIT_MIN, WAIT_MAX)

    @task(7)
    def search_trips(self) -> None:
        if random.random() < HOT_RATIO:
            origin_id, dest_id = random.choice(HOT_ROUTES)
            dep_date = random.choice(HOT_DATES)
            label = "GET /api/trips [hot-80%]"
        else:
            origin_id, dest_id = random.sample(LOCATION_IDS, 2)
            dep_date = random.choice(ALL_DATES)
            label = "GET /api/trips [cold-20%]"

        with self.client.get(
            "/api/trips",
            params={
                "originId": origin_id,
                "destinationId": dest_id,
                "departureDate": dep_date,
            },
            name=label,
            catch_response=True,
            timeout=15,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(2)
    def search_locations(self) -> None:
        q = random.choice(LOCATION_QUERIES)
        params = {"q": q} if q else None
        with self.client.get(
            "/api/locations",
            params=params,
            name="GET /api/locations",
            catch_response=True,
            timeout=15,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(1)
    def get_trip_detail(self) -> None:
        trip_id = random.randint(1, 1500)
        with self.client.get(
            f"/api/trips/{trip_id}",
            name="GET /api/trips/{id}",
            catch_response=True,
            timeout=15,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")
