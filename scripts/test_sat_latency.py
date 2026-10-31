"""Offline fixtures only; no device or network access."""
import copy
import pathlib
import runpy
import unittest

analyze = runpy.run_path(str(pathlib.Path(__file__).with_name("sat_latency.py")))["analyze"]


def samples(n=300, metric="event-redraw", duration=20):
    return [dict(sample_id=str(i), metric=metric, device="esp32", scenario="normal",
                 start_clock="camera-1", end_clock="camera-1", start_ms=str(i * 1000),
                 end_ms=str(i * 1000 + duration), uncertainty_ms="2", outcome="ok",
                 evidence_kind="SIMULATED", evidence_ref="fixture/frame-" + str(i))
            for i in range(n)]


class LatencyCheck(unittest.TestCase):
    def test_nearest_rank_and_uncertainty(self):
        rows = samples()
        for i, row in enumerate(rows):
            row["end_ms"] = str(i * 1000 + i + 1)
        group = analyze(rows)["groups"][0]
        self.assertEqual(group["p95_upper_ms"], 287)
        self.assertEqual(group["max_upper_ms"], 302)
        self.assertEqual(group["result"], "PASS")

    def test_insufficient(self):
        self.assertEqual(analyze(samples(299))["groups"][0]["result"], "BLOCKED")

    def test_tail_not_dropped(self):
        self.assertEqual(analyze(samples(duration=501))["groups"][0]["result"], "FAIL")

    def test_network_uses_max(self):
        rows = samples(30, "network-unknown", 2500)
        rows[-1]["end_ms"] = str(29000 + 3001)
        self.assertEqual(analyze(rows)["groups"][0]["result"], "FAIL")

    def test_physical_per_device(self):
        rows = samples(30, "physical-display", 998)
        other = copy.deepcopy(rows[:29])
        for row in other:
            row["device"] = "scanner"
        self.assertEqual([g["result"] for g in analyze(rows + other)["groups"]], ["PASS", "BLOCKED"])

    def test_lost_samples_fail(self):
        for outcome in ("timeout", "missed", "coalesced"):
            rows = samples()
            rows[0].update(outcome=outcome, start_ms="", end_ms="")
            self.assertEqual(analyze(rows)["groups"][0]["result"], "FAIL")

    def test_duplicate(self):
        rows = samples()
        with self.assertRaises(ValueError):
            analyze(rows + rows[:1])

    def test_unsynced_clocks(self):
        rows = samples()
        rows[0]["end_clock"] = "esp-millis"
        with self.assertRaises(ValueError):
            analyze(rows)

    def test_bad_numbers(self):
        for field, value in (("end_ms", "nan"), ("end_ms", "inf"), ("end_ms", "-1"),
                             ("uncertainty_ms", "-1"), ("start_ms", "oops")):
            rows = samples()
            rows[0][field] = value
            with self.assertRaises(ValueError):
                analyze(rows)

    def test_missing_provenance_or_unknown_enum(self):
        for field, value in (("evidence_ref", ""), ("metric", "write"), ("outcome", "skip"),
                             ("evidence_kind", ""), ("start_clock", ""), ("scenario", "")):
            rows = samples()
            rows[0][field] = value
            with self.assertRaises(ValueError):
                analyze(rows)

    def test_simulation_never_claims_hardware(self):
        report = analyze(samples())
        self.assertEqual(report["hardware_acceptance"], "NOT_ASSESSED")
        self.assertEqual(report["groups"][0]["evidence_kind"], "SIMULATED")

    def test_mixed_evidence_rejected(self):
        rows = samples()
        rows[0]["evidence_kind"] = "HARDWARE"
        with self.assertRaises(ValueError):
            analyze(rows)

    def test_empty(self):
        with self.assertRaises(ValueError):
            analyze([])


if __name__ == "__main__":
    unittest.main()
