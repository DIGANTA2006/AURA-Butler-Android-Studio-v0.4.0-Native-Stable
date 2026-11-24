"""Conservative, deterministic caller consent parsing for transcript retention."""
from __future__ import annotations

import re
from dataclasses import dataclass


def classify_transcript_consent(text: object, language: object) -> bool | None:
    """Return True/False for clear consent, or None for ambiguous/unsupported speech.

    This parser is intentionally narrow. It never asks an LLM to decide whether a
    caller consented. Mixed yes/no statements are treated as ambiguous.
    """
    if not isinstance(text, str) or not isinstance(language, str):
        return None
    normalized = re.sub(r"\s+", " ", text.casefold()).strip()
    if not normalized or len(normalized) > 500:
        return None

    language = language.lower().split("-")[0]
    if language == "en":
        partial_choice = re.search(
            r"\b(?:only|just)\b.{0,40}\b(?:transcript|summary)\b|"
            r"\b(?:transcript|summary)\b.{0,40}\b(?:only|just)\b",
            normalized,
        )
        if partial_choice or any(term in normalized for term in (
            "not sure", "unsure", "maybe", "i am uncertain", "i don't know", "i do not know",
            "without transcript", "without summary", "not the transcript",
        )):
            return None
        no_terms = ("no", "no thanks", "do not save", "don't save", "do not want", "don't want", "not agree", "i refuse", "i decline")
        yes_terms = ("yes", "okay", "ok", "sure", "i agree", "i consent", "you may", "go ahead")
        has_no = any(re.search(rf"\b{re.escape(term)}\b", normalized) for term in no_terms)
        has_yes = any(re.search(rf"\b{re.escape(term)}\b", normalized) for term in yes_terms)
    elif language == "bn":
        partial_choice = any(term in normalized for term in ("শুধু", "কেবল")) and any(
            term in normalized for term in ("প্রতিলিপি", "সারাংশ")
        )
        if partial_choice or any(term in normalized for term in (
            "নিশ্চিত নই", "হয়তো", "হয়তো", "প্রতিলিপি ছাড়া", "প্রতিলিপি ছাড়া",
            "সারাংশ ছাড়া", "সারাংশ ছাড়া",
        )):
            return None
        no_terms = ("না", "রাজি নই", "সম্মতি দিচ্ছি না", "চাই না")
        yes_terms = ("হ্যাঁ", "হ্যা", "ঠিক আছে", "রাজি", "সম্মতি দিচ্ছি", "করতে পারেন")
        has_no = any(term in normalized for term in no_terms)
        has_yes = any(term in normalized for term in yes_terms)
    elif language == "hi":
        partial_choice = any(term in normalized for term in ("सिर्फ", "केवल")) and any(
            term in normalized for term in ("ट्रांसक्रिप्ट", "सारांश")
        )
        if partial_choice or any(term in normalized for term in (
            "शायद", "पता नहीं", "निश्चित नहीं", "ट्रांसक्रिप्ट के बिना", "सारांश के बिना",
        )):
            return None
        no_terms = ("नहीं", "नही", "मना", "मत रखिए", "मत रखें", "न रखें", "न रखिए", "सहमत नहीं", "नहीं चाहिए")
        yes_terms = ("हाँ", "हां", "ठीक है", "मंजूर", "मंज़ूर", "सहमत", "कर सकते हैं")
        has_no = any(term in normalized for term in no_terms)
        has_yes = any(term in normalized for term in yes_terms)
    else:
        return None

    if has_no and has_yes:
        return None
    if has_no:
        return False
    if has_yes:
        return True
    return None


@dataclass
class TranscriptConsentGate:
    """One-call consent state. Two ambiguous answers default to no retention."""

    max_ambiguous_answers: int = 2
    attempts: int = 0
    decided: bool = False
    consent: bool = False

    def submit(self, text: object, language: object) -> bool | None:
        if self.decided:
            return self.consent
        decision = classify_transcript_consent(text, language)
        if decision is None:
            self.attempts += 1
            if self.attempts >= self.max_ambiguous_answers:
                self.decided = True
                self.consent = False
                return False
            return None
        self.decided = True
        self.consent = decision
        return decision
