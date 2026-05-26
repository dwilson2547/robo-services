from __future__ import annotations

from datetime import datetime, timezone

from ..models import DropReason, RawFrame, SignalEvent
from ..probe import detect_probe
from ..profiles import DecodeFailureError, UnknownCanIdError, VehicleCanProfile


def utcnow() -> datetime:
    return datetime.now(tz=timezone.utc)


class FrameInvalidError(ValueError):
    """Raised when a frame is structurally invalid for ingestion."""


class IngestNormalizer:
    def __init__(self, profile: VehicleCanProfile) -> None:
        self._profile = profile

    def normalize_frame(self, frame: RawFrame, *, source_session: str) -> list[SignalEvent]:
        self._validate_frame(frame)
        probe = detect_probe(frame)
        processed_at = utcnow()
        if probe is not None:
            return [
                SignalEvent(
                    signal_name="ProbeFrame",
                    value=1.0,
                    unit="count",
                    raw_frame_id=frame.can_id,
                    captured_at=frame.captured_at,
                    processed_at=processed_at,
                    source_session=source_session,
                    probe=probe,
                )
            ]
        decoded_signals = self._profile.decoder.decode(frame)
        return [
            SignalEvent(
                signal_name=signal.name,
                value=signal.value,
                unit=signal.unit,
                raw_frame_id=frame.can_id,
                captured_at=frame.captured_at,
                processed_at=processed_at,
                source_session=source_session,
            )
            for signal in decoded_signals
        ]

    @staticmethod
    def classify_error(exc: Exception) -> DropReason:
        if isinstance(exc, UnknownCanIdError):
            return DropReason.UNKNOWN_CAN_ID
        if isinstance(exc, DecodeFailureError):
            return DropReason.DBC_DECODE_FAILED
        return DropReason.FRAME_INVALID

    @staticmethod
    def _validate_frame(frame: RawFrame) -> None:
        if frame.dlc < 0 or frame.dlc > 8:
            raise FrameInvalidError("frame DLC must be between 0 and 8")
        if frame.timestamp_ms < 0:
            raise FrameInvalidError("frame timestamp must be non-negative")
