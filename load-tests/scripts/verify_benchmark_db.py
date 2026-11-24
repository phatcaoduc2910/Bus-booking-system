"""Post-benchmark PostgreSQL consistency & duplicate-booking verifier (uses psql CLI).

Usage examples:
  # Check seat consistency across all trips (any scenario):
  python load-tests/scripts/verify_benchmark_db.py --db-url postgresql://user:pass@localhost:5435/busbooking

  # Check Last-Seat Contention (Trip 10000):
  python load-tests/scripts/verify_benchmark_db.py --mode last-seat --db-url ...

  # Check Mixed + Retry duplicate count against Locust summary JSON:
  python load-tests/scripts/verify_benchmark_db.py --mode mixed-retry --summary retry_summary.json --db-url ...
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict


def run_psql_scalar(db_url: str, sql: str) -> str:
    cmd = ["psql", db_url, "-v", "ON_ERROR_STOP=1", "-t", "-A", "-c", sql]
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"psql failed ({proc.returncode}): {proc.stderr.strip()}")
    return proc.stdout.strip()


def verify(args: argparse.Namespace) -> Dict[str, Any]:
    negative_seats = int(run_psql_scalar(
        args.db_url,
        "SELECT count(*) FROM trips WHERE available_seats < 0;",
    ))
    seat_balance_mismatches = int(run_psql_scalar(
        args.db_url,
        """
        SELECT count(*)
        FROM trips t
        JOIN vehicles v ON v.id = t.vehicle_id
        LEFT JOIN (
            SELECT trip_id, COALESCE(SUM(quantity), 0) AS booked
            FROM bookings
            WHERE status = 'CONFIRMED'
            GROUP BY trip_id
        ) b ON b.trip_id = t.id
        WHERE t.available_seats + COALESCE(b.booked, 0) <> v.seat_capacity;
        """,
    ))
    total_bookings = int(run_psql_scalar(args.db_url, "SELECT count(*) FROM bookings;"))

    report: Dict[str, Any] = {
        "mode": args.mode,
        "negative_seat_trips": negative_seats,
        "seat_balance_mismatches": seat_balance_mismatches,
        "total_bookings": total_bookings,
        "seat_integrity_ok": (negative_seats == 0 and seat_balance_mismatches == 0),
    }

    if args.mode == "last-seat":
        trip_id = args.contention_trip_id
        avail = int(run_psql_scalar(
            args.db_url,
            f"SELECT available_seats FROM trips WHERE id = {trip_id};",
        ))
        confirmed = int(run_psql_scalar(
            args.db_url,
            f"SELECT COALESCE(SUM(quantity), 0) FROM bookings WHERE trip_id = {trip_id} AND status = 'CONFIRMED';",
        ))
        report["contention_trip_id"] = trip_id
        report["contention_available_seats"] = avail
        report["contention_confirmed_seats"] = confirmed
        report["contention_exact_40_sold"] = (confirmed == 40 and avail == 0)

    if args.mode == "mixed-retry":
        new_bookings = total_bookings - args.seeded_bookings
        report["seeded_bookings"] = args.seeded_bookings
        report["actual_new_bookings"] = new_bookings
        if args.summary:
            summary = json.loads(Path(args.summary).read_text(encoding="utf-8"))
            logical = int(summary["logical_bookings_created"])
            report["logical_bookings_created"] = logical
            report["retries_sent"] = int(summary.get("retries_sent", 0))
            report["retries_replayed_header"] = int(summary.get("retries_replayed_header", 0))
            report["duplicate_bookings"] = new_bookings - logical

    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify BusBooking PostgreSQL state after load tests.")
    parser.add_argument("--db-url", required=True, help="PostgreSQL connection URI for psql.")
    parser.add_argument(
        "--mode",
        choices=["general", "last-seat", "mixed-retry"],
        default="general",
        help="Verification mode matching the Locust scenario.",
    )
    parser.add_argument("--contention-trip-id", type=int, default=10000)
    parser.add_argument("--seeded-bookings", type=int, default=5000)
    parser.add_argument("--summary", default="", help="Path to JSON summary written by Locust.")
    args = parser.parse_args()

    report = verify(args)
    print(json.dumps(report, indent=2))
    return 0 if report["seat_integrity_ok"] else 2


if __name__ == "__main__":
    sys.exit(main())
