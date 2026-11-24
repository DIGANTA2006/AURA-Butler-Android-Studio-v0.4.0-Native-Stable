"""Conservative Whisper segment-quality heuristics (not calibrated probabilities)."""
from __future__ import annotations

import math


# Whisper exposes avg_logprob, no_speech_prob and compression_ratio per segment.
# These operational thresholds flag likely unusable segments; they are not a
# probability that the transcript is correct and must be tuned with real audio.
MIN_AVERAGE_LOG_PROBABILITY = -1.2
MAX_AVERAGE_NO_SPEECH_PROBABILITY = 0.70
MAX_SEGMENT_COMPRESSION_RATIO = 2.8


def transcription_needs_repeat(
    text: object,
    average_log_probability: object,
    average_no_speech_probability: object,
    max_compression_ratio: object,
) -> bool:
    """Return True when transcript is empty or Whisper diagnostics look weak."""
    if not isinstance(text, str) or not text.strip():
        return True
    try:
        avg_logprob = float(average_log_probability)
        no_speech = float(average_no_speech_probability)
        compression = float(max_compression_ratio)
    except (TypeError, ValueError, OverflowError):
        return True
    if not all(math.isfinite(value) for value in (avg_logprob, no_speech, compression)):
        return True
    if not (0.0 <= no_speech <= 1.0) or compression < 0.0:
        return True
    return (
        avg_logprob < MIN_AVERAGE_LOG_PROBABILITY
        or no_speech > MAX_AVERAGE_NO_SPEECH_PROBABILITY
        or compression > MAX_SEGMENT_COMPRESSION_RATIO
    )
