from __future__ import annotations

import unittest

from aura_privacy import TranscriptConsentGate, classify_transcript_consent


class TranscriptConsentTests(unittest.TestCase):
    def test_english_clear_yes_and_no(self) -> None:
        self.assertIs(classify_transcript_consent("Yes, you may save it.", "en"), True)
        self.assertIs(classify_transcript_consent("No, do not save a transcript.", "en"), False)
        self.assertIs(classify_transcript_consent("I don't know.", "en"), None)
        self.assertIs(classify_transcript_consent("I'm not sure.", "en"), None)

    def test_bengali_clear_yes_and_no(self) -> None:
        self.assertIs(classify_transcript_consent("হ্যাঁ, ঠিক আছে।", "bn"), True)
        self.assertIs(classify_transcript_consent("না, চাই না।", "bn"), False)

    def test_hindi_clear_yes_and_no(self) -> None:
        self.assertIs(classify_transcript_consent("हाँ, ठीक है।", "hi"), True)
        self.assertIs(classify_transcript_consent("नहीं, मत रखिए।", "hi"), False)

    def test_consent_gate_requires_clear_yes_and_defaults_to_no_after_two_ambiguous_answers(self) -> None:
        gate = TranscriptConsentGate()
        self.assertIsNone(gate.submit("I am not sure.", "en"))
        self.assertFalse(gate.decided)
        self.assertIs(gate.submit("Maybe later.", "en"), False)
        self.assertTrue(gate.decided)
        self.assertFalse(gate.consent)
        self.assertIs(gate.submit("Yes", "en"), False)

    def test_explicit_no_decides_immediately(self) -> None:
        gate = TranscriptConsentGate()
        self.assertIs(gate.submit("No, thanks.", "en"), False)
        self.assertTrue(gate.decided)

    def test_explicit_yes_decides_immediately(self) -> None:
        gate = TranscriptConsentGate()
        self.assertIs(gate.submit("Yes, you may save it.", "en"), True)
        self.assertTrue(gate.decided)

    def test_partial_transcript_or_summary_consent_is_ambiguous(self) -> None:
        self.assertIsNone(classify_transcript_consent("Yes, summary only please.", "en"))
        self.assertIsNone(classify_transcript_consent("Yes, just the summary please.", "en"))
        self.assertIsNone(classify_transcript_consent("হ্যাঁ, শুধু সারাংশ।", "bn"))
        self.assertIsNone(classify_transcript_consent("हाँ, सिर्फ सारांश।", "hi"))
        self.assertIs(classify_transcript_consent("नहीं, ट्रांसक्रिप्ट मत रखिए।", "hi"), False)

    def test_ambiguous_mixed_or_unsupported_responses_fail_closed(self) -> None:
        self.assertIsNone(classify_transcript_consent("Maybe, I am not sure.", "en"))
        self.assertIsNone(classify_transcript_consent("হয়তো", "bn"))
        self.assertIsNone(classify_transcript_consent("शायद", "hi"))
        self.assertIsNone(classify_transcript_consent("Yes, but no transcript.", "en"))
        self.assertIsNone(classify_transcript_consent("Yes", "ta"))
        self.assertIsNone(classify_transcript_consent(None, "en"))


if __name__ == "__main__":
    unittest.main()
