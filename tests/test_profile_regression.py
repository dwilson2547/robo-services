from __future__ import annotations

import unittest

from can_pub_sub_probe.hops.ingest import IngestNormalizer
from can_pub_sub_probe.models import RawFrame
from can_pub_sub_probe.profiles import build_impala_2008_can_profile


class ProfileRegressionTests(unittest.TestCase):
    def test_impala_profile_decodes_documented_signals(self) -> None:
        normalizer = IngestNormalizer(build_impala_2008_can_profile())
        frames = [
            RawFrame(
                timestamp_ms=1_000,
                can_id=0x0C9,
                dlc=6,
                data=bytes([0x00, 0x0F, 0xA0, 0x00, 0x7F, 0x01]),
            ),
            RawFrame(
                timestamp_ms=1_050,
                can_id=0x3E9,
                dlc=2,
                data=bytes([0x13, 0x88]),
            ),
            RawFrame(
                timestamp_ms=1_100,
                can_id=0x4C1,
                dlc=5,
                data=bytes([0x00, 0x00, 0x78, 0x50, 0x64]),
            ),
            RawFrame(
                timestamp_ms=1_150,
                can_id=0x4D1,
                dlc=3,
                data=bytes([0x00, 0x00, 0x6E]),
            ),
            RawFrame(
                timestamp_ms=1_200,
                can_id=0x1EF,
                dlc=4,
                data=bytes([0x00, 0x00, 0x30, 0x39]),
            ),
            RawFrame(
                timestamp_ms=1_250,
                can_id=0x135,
                dlc=1,
                data=bytes([0x04]),
            ),
        ]

        events = []
        for frame in frames:
            events.extend(normalizer.normalize_frame(frame, source_session="profile-regression"))

        by_name = {event.signal_name: event.value for event in events}
        self.assertEqual(
            set(by_name),
            {
                "EngineRPM",
                "ThrottlePosition",
                "BrakePedalApplied",
                "VehicleSpeed",
                "CoolantTemp",
                "IntakeAirTemp",
                "AmbientTemp",
                "EngineOilTemp",
                "MassAirFlow",
                "TransmissionGear",
            },
        )
        self.assertEqual(by_name["EngineRPM"], 1000.0)
        self.assertAlmostEqual(by_name["ThrottlePosition"], 49.8039215686)
        self.assertEqual(by_name["BrakePedalApplied"], 1.0)
        self.assertEqual(by_name["VehicleSpeed"], 50.0)
        self.assertEqual(by_name["CoolantTemp"], 80.0)
        self.assertEqual(by_name["IntakeAirTemp"], 40.0)
        self.assertEqual(by_name["AmbientTemp"], 10.0)
        self.assertEqual(by_name["EngineOilTemp"], 70.0)
        self.assertAlmostEqual(by_name["MassAirFlow"], 123.45)
        self.assertEqual(by_name["TransmissionGear"], 4.0)
