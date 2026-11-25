"""Unit tests for dbc_check: bit decoding, DBC parsing, and the validators' verdicts.

    python3 test_dbc_check.py   (from can_data/tools/)
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dbc_check as C  # noqa: E402

DBC = '''BO_ 535 WheelSpeed: 8 ABS
 SG_ WhlFl_W_Meas : 7|14@0+ (0.04,0) [0|0] "rad/s" XXX
 SG_ Mux m1 : 20|4@1+ (1,0) [0|0] "" XXX
BO_ 516 Eng: 8 PCM
 SG_ Neg : 0|8@1- (1,0) [0|0] "" XXX
'''


class Decode(unittest.TestCase):
    def test_motorola_14_bit_across_a_byte(self):
        raw = 1234
        data = bytes([raw >> 6, (raw & 0x3F) << 2, 0, 0, 0, 0, 0, 0])
        sig = C.Signal("m", "s", 7, 14, False, False, 0.04, 0, "rad/s")
        self.assertAlmostEqual(sig.decode(data), raw * 0.04)

    def test_intel_12_bit_and_signed(self):
        sig = C.Signal("m", "s", 0, 12, True, False, 1, 0, "")
        self.assertEqual(sig.decode(bytes([0xCD, 0x0A])), 0xACD)
        neg = C.Signal("m", "s", 0, 8, True, True, 1, 0, "")
        self.assertEqual(neg.decode(bytes([0xFF])), -1)

    def test_short_data_is_none_not_an_error(self):
        self.assertIsNone(C.Signal("m", "s", 40, 8, False, False, 1, 0, "").decode(b"\x00\x01"))


class Parse(unittest.TestCase):
    def test_messages_signals_and_mux_skipped(self):
        with tempfile.NamedTemporaryFile("w", suffix=".dbc", delete=False) as f:
            f.write(DBC)
        msgs = C.parse_dbc(f.name)
        os.unlink(f.name)
        self.assertEqual(set(msgs), {535, 516})
        self.assertEqual([s.name for s in msgs[535][1]], ["WhlFl_W_Meas"])  # multiplexed one skipped
        self.assertTrue(msgs[516][1][0].signed and msgs[516][1][0].little)


class Verdicts(unittest.TestCase):
    def gps(self, n=200):
        return {(0, i): (i % 60) * 1.5 + 10 for i in range(n)}  # always moving

    def test_speed_signal_tracking_gps_passes(self):
        g = self.gps()
        sig = C.Signal("m", "s", 0, 8, True, False, 1, 0, "km/h")
        status, detail, m = C.check_speed(sig, {k: v * 0.9 for k, v in g.items()}, g)
        self.assertEqual(status, "PASS")
        self.assertAlmostEqual(m["k"], 1 / 0.9, places=2)

    def test_unrelated_signal_fails_speed(self):
        g = self.gps()
        sig = C.Signal("m", "s", 0, 8, True, False, 1, 0, "km/h")
        noise = {k: (k[1] * 7919) % 100 for k in g}
        self.assertEqual(C.check_speed(sig, noise, g)[0], "FAIL")

    def test_speed_without_moving_data_is_inconclusive_not_pass(self):
        g = {(0, i): 0.0 for i in range(100)}
        sig = C.Signal("m", "s", 0, 8, True, False, 1, 0, "km/h")
        self.assertEqual(C.check_speed(sig, dict(g), g)[0], "INCONCLUSIVE")

    def test_constant_signal_is_not_rpm(self):
        g = self.gps()
        stop = {(0, 1000 + i): 0.0 for i in range(30)}
        g.update(stop)
        series = {k: 1650.0 for k in g}
        self.assertEqual(C.check_rpm(None, series, g)[0], "FAIL")

    def test_geared_rpm_passes_and_ungeared_is_only_plausible(self):
        g, series = {}, {}
        for i in range(400):  # four gears, each held for 100 s at its own rpm/speed
            ratio = (45, 30, 22, 17)[i // 100]
            g[(0, i)] = 40.0 + (i % 100) * 0.3
            series[(0, i)] = ratio * g[(0, i)]
        for i in range(400, 440):  # stopped, idling
            g[(0, i)] = 0.0
            series[(0, i)] = 900.0
        self.assertEqual(C.check_rpm(None, series, g)[0], "PASS")
        slip = dict(series)
        for k in list(slip):
            if g[k] > 0:  # smear rpm/speed so no level is held
                slip[k] = series[k] * (1 + ((k[1] * 7919) % 100) / 100)
        self.assertEqual(C.check_rpm(None, slip, g)[0], "PLAUSIBLE")

    def drive(self):
        """Gears 1-4 held 40 s each at rising speed, then idle; ratios are smeared so no level is held."""
        g, gear, rpm = {}, {}, {}
        for i in range(160):
            n = i // 40 + 1
            g[(0, i)] = 12.0 * n + (i % 40) * 0.2
            gear[(0, i)] = float(n)
            r = (115, 76, 46, 29)[n - 1] * (1 + ((i * 7919) % 100) / 400)  # up to +25 % smear, so no single level is held
            rpm[(0, i)] = r * g[(0, i)]
        for i in range(160, 200):
            g[(0, i)], gear[(0, i)], rpm[(0, i)] = 0.0, 1.0, 800.0
        return g, gear, rpm

    def test_gear_rising_with_speed_passes_and_a_counter_fails(self):
        g, gear, _ = self.drive()
        self.assertEqual(C.check_gear(None, gear, g)[0], "PASS")
        counter = {k: float(k[1] % 5) for k in g}  # cycles regardless of speed
        self.assertEqual(C.check_gear(None, counter, g)[0], "FAIL")
        self.assertEqual(C.check_gear(None, {k: 1.0 for k in g}, g)[0], "INCONCLUSIVE")

    def test_rpm_separating_by_gear_upgrades_plausible_to_pass(self):
        g, gear, rpm = self.drive()
        self.assertEqual(C.check_rpm(None, rpm, g)[0], "PLAUSIBLE")
        self.assertEqual(C.check_rpm(None, rpm, g, gear=gear)[0], "PASS")

    def test_rpm_that_does_not_follow_the_gear_is_not_upgraded(self):
        g, gear, rpm = self.drive()
        shuffled = {k: gear[(0, (k[1] + 40) % 160)] if k[1] < 160 else gear[k] for k in gear}
        self.assertEqual(C.check_rpm(None, rpm, g, gear=shuffled)[0], "PLAUSIBLE")

    def test_pedal_out_of_range_fails(self):
        g = self.gps()
        self.assertEqual(C.check_pedal(None, {k: 250.0 * (k[1] % 2) for k in g}, g)[0], "FAIL")


if __name__ == "__main__":
    unittest.main()
