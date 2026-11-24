from __future__ import annotations

import unittest

from aura_asr_quality import transcription_needs_repeat


class WhisperQualityHeuristicTests(unittest.TestCase):
    def test_healthy_segment_diagnostics_do_not_force_a_repeat(self) -> None:
        self.assertFalse(transcription_needs_repeat("Please call me tomorrow.", -0.35, 0.08, 1.2))

    def test_empty_or_missing_diagnostics_fail_closed(self) -> None:
        self.assertTrue(transcription_needs_repeat("", -0.2, 0.05, 1.1))
        self.assertTrue(transcription_needs_repeat("Hello", None, 0.05, 1.1))

    def test_weak_average_log_probability_requests_repeat(self) -> None:
        self.assertTrue(transcription_needs_repeat("uncertain words", -1.4, 0.1, 1.5))

    def test_high_no_speech_probability_requests_repeat(self) -> None:
        self.assertTrue(transcription_needs_repeat("possibly background noise", -0.4, 0.81, 1.2))

    def test_extreme_compression_ratio_requests_repeat(self) -> None:
        self.assertTrue(transcription_needs_repeat("repetitive output", -0.4, 0.1, 3.1))

    def test_non_finite_or_invalid_metrics_fail_closed(self) -> None:
        self.assertTrue(transcription_needs_repeat("hello", float("nan"), 0.1, 1.1))
        self.assertTrue(transcription_needs_repeat("hello", -0.4, 1.5, 1.1))


if __name__ == "__main__":
    unittest.main()
