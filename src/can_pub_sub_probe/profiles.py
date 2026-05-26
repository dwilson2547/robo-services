from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass

from .interfaces import FrameDecoder
from .models import DecodedSignal, RawFrame


class UnknownCanIdError(LookupError):
    """Raised when a frame ID is not defined for the current vehicle CAN profile."""


class DecodeFailureError(ValueError):
    """Raised when a frame cannot be decoded according to its signal definitions."""


@dataclass(frozen=True, slots=True)
class SignalDefinition:
    name: str
    unit: str
    minimum_dlc: int
    extractor: Callable[[RawFrame], float]


@dataclass(frozen=True, slots=True)
class VehicleCanProfile:
    name: str
    vehicle: str
    bus: str
    baud: int
    accepted_can_ids: frozenset[int]
    signal_versions: Mapping[str, int]
    decoder: FrameDecoder


class StaticFrameDecoder:
    def __init__(self, definitions: Mapping[int, Sequence[SignalDefinition]]) -> None:
        self._definitions = {
            can_id: tuple(signal_definitions)
            for can_id, signal_definitions in definitions.items()
        }

    @property
    def accepted_can_ids(self) -> frozenset[int]:
        return frozenset(self._definitions)

    def decode(self, frame: RawFrame) -> Sequence[DecodedSignal]:
        if frame.can_id not in self._definitions:
            raise UnknownCanIdError(f"CAN ID 0x{frame.can_id:X} is not defined")
        decoded: list[DecodedSignal] = []
        for signal_definition in self._definitions[frame.can_id]:
            require_minimum_dlc(frame, signal_definition.minimum_dlc)
            try:
                value = float(signal_definition.extractor(frame))
            except (IndexError, TypeError, ValueError) as exc:
                raise DecodeFailureError(
                    f"failed to decode {signal_definition.name} from 0x{frame.can_id:X}"
                ) from exc
            decoded.append(
                DecodedSignal(
                    name=signal_definition.name,
                    value=value,
                    unit=signal_definition.unit,
                )
            )
        return decoded


def require_minimum_dlc(frame: RawFrame, minimum_dlc: int) -> None:
    if frame.dlc < minimum_dlc:
        raise DecodeFailureError(
            f"frame 0x{frame.can_id:X} requires DLC >= {minimum_dlc}, got {frame.dlc}"
        )


def _u16_be(frame: RawFrame, index: int) -> int:
    return (frame.data[index] << 8) | frame.data[index + 1]


def build_impala_2008_can_profile() -> VehicleCanProfile:
    definitions: dict[int, tuple[SignalDefinition, ...]] = {
        0x0C9: (
            SignalDefinition(
                name="EngineRPM",
                unit="rpm",
                minimum_dlc=3,
                extractor=lambda frame: _u16_be(frame, 1) * 0.25,
            ),
            SignalDefinition(
                name="ThrottlePosition",
                unit="percent",
                minimum_dlc=5,
                extractor=lambda frame: frame.data[4] / 2.55,
            ),
            SignalDefinition(
                name="BrakePedalApplied",
                unit="bool",
                minimum_dlc=6,
                extractor=lambda frame: 1.0 if frame.data[5] & 0x01 else 0.0,
            ),
        ),
        0x3E9: (
            SignalDefinition(
                name="VehicleSpeed",
                unit="mph",
                minimum_dlc=2,
                extractor=lambda frame: _u16_be(frame, 0) * 0.01,
            ),
        ),
        0x4C1: (
            SignalDefinition(
                name="CoolantTemp",
                unit="degC",
                minimum_dlc=3,
                extractor=lambda frame: frame.data[2] - 40,
            ),
            SignalDefinition(
                name="IntakeAirTemp",
                unit="degC",
                minimum_dlc=4,
                extractor=lambda frame: frame.data[3] - 40,
            ),
            SignalDefinition(
                name="AmbientTemp",
                unit="degC",
                minimum_dlc=5,
                extractor=lambda frame: (frame.data[4] / 2) - 40,
            ),
        ),
        0x4D1: (
            SignalDefinition(
                name="EngineOilTemp",
                unit="degC",
                minimum_dlc=3,
                extractor=lambda frame: frame.data[2] - 40,
            ),
        ),
        0x1EF: (
            SignalDefinition(
                name="MassAirFlow",
                unit="g/s",
                minimum_dlc=4,
                extractor=lambda frame: _u16_be(frame, 2) / 100,
            ),
        ),
        0x135: (
            SignalDefinition(
                name="TransmissionGear",
                unit="gear",
                minimum_dlc=1,
                extractor=lambda frame: frame.data[0],
            ),
        ),
    }
    decoder = StaticFrameDecoder(definitions)
    signal_versions = {
        signal_definition.name: 1
        for signal_definitions in definitions.values()
        for signal_definition in signal_definitions
    }
    return VehicleCanProfile(
        name="gm-global-a-2008-impala-hs-can",
        vehicle="2008 Chevrolet Impala",
        bus="HS-CAN",
        baud=500_000,
        accepted_can_ids=decoder.accepted_can_ids,
        signal_versions=signal_versions,
        decoder=decoder,
    )
