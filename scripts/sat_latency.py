"""Analyze externally collected SAT CSV; never contacts or controls hardware.

Use a single reference clock for both endpoints (e.g. external video/logic analyzer).
Include the total clock/timestamp uncertainty, in milliseconds. Do not subtract
Java nanoTime from ESP32 millis. PASS means only the supplied data meets a bound.
"""
import argparse
import csv
import json
import math
from decimal import Decimal, InvalidOperation

LIMITS = {"event-redraw": (300, 500, "p95"),
          "physical-display": (30, 1000, "p95"),
          "network-unknown": (30, 3000, "max")}
FIELDS = ("sample_id", "metric", "device", "scenario", "start_clock", "end_clock",
          "start_ms", "end_ms", "uncertainty_ms", "outcome", "evidence_kind", "evidence_ref")


def number(row, field):
    try:
        value = Decimal(row[field])
    except (InvalidOperation, TypeError, ValueError) as error:
        raise ValueError("Invalid number: " + field) from error
    if not value.is_finite() or value < 0 or value > Decimal("1e18"):
        raise ValueError("Out of range: " + field)
    return value


def analyze(rows):
    groups, seen = {}, set()
    for row in rows:
        if any(not isinstance(row.get(f), str) for f in FIELDS):
            raise ValueError("Missing CSV columns")
        for field in ("sample_id", "device", "scenario", "start_clock", "end_clock", "evidence_ref"):
            if not row[field].strip():
                raise ValueError("Missing provenance: " + field)
        if row["metric"] not in LIMITS or row["outcome"] not in ("ok", "timeout", "missed", "coalesced"):
            raise ValueError("Unknown metric/outcome")
        if row["evidence_kind"] not in ("SIMULATED", "HARDWARE"):
            raise ValueError("Unknown evidence kind")
        if row["start_clock"] != row["end_clock"]:
            raise ValueError("Endpoints must share the same verified reference clock")
        key = (row["metric"], row["device"], row["scenario"])
        identity = key + (row["sample_id"],)
        if identity in seen:
            raise ValueError("Duplicate sample identity")
        seen.add(identity)
        group = groups.setdefault(key, dict(values=[], outcomes={}, evidence_kind=row["evidence_kind"]))
        if group["evidence_kind"] != row["evidence_kind"]:
            raise ValueError("Cannot combine hardware and simulation in one group")
        uncertainty = number(row, "uncertainty_ms")
        outcome = row["outcome"]
        group["outcomes"][outcome] = group["outcomes"].get(outcome, 0) + 1
        if outcome == "ok":
            start, end = number(row, "start_ms"), number(row, "end_ms")
            if end < start:
                raise ValueError("Negative duration or unhandled clock wrap")
            group["values"].append(end - start + uncertainty)
    if not groups:
        raise ValueError("No samples; SAT NOT RUN")
    report = dict(scope="OFFLINE_ANALYSIS_ONLY", hardware_acceptance="NOT_ASSESSED", groups=[])
    for key, group in sorted(groups.items()):
        values = sorted(group["values"])
        minimum, bound, statistic = LIMITS[key[0]]
        total, valid = sum(group["outcomes"].values()), len(values)
        p50 = values[math.ceil(valid * .50) - 1] if valid else None
        p95 = values[math.ceil(valid * .95) - 1] if valid else None
        maximum = values[-1] if valid else None
        measured = maximum if statistic == "max" else p95
        result = "PASS"
        if valid != total or (measured is not None and measured > bound):
            result = "FAIL"
        elif valid < minimum:
            result = "BLOCKED"
        report["groups"].append(dict(metric=key[0], device=key[1], scenario=key[2],
            evidence_kind=group["evidence_kind"], total=total, valid=valid,
            outcomes=group["outcomes"], minimum=minimum, limit_ms=bound, statistic=statistic,
            p50_upper_ms=float(p50) if p50 is not None else None,
            p95_upper_ms=float(p95) if p95 is not None else None,
            max_upper_ms=float(maximum) if maximum is not None else None, result=result))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file")
    args = parser.parse_args()
    try:
        with open(args.csv_file, encoding="utf-8-sig", newline="") as stream:
            report = analyze(csv.DictReader(stream))
    except (OSError, ValueError) as error:
        parser.exit(2, "Invalid evidence: " + str(error) + "\n")
    print(json.dumps(report, indent=2, allow_nan=False))
    return 0 if all(g["result"] == "PASS" for g in report["groups"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
