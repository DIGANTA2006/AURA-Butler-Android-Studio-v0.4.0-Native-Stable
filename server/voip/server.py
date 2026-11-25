from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import re
import shutil
import tempfile
import wave
from collections import deque
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import av
import numpy as np
import webrtcvad
from aiortc import AudioStreamTrack, RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription
from av import AudioFrame
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from faster_whisper import WhisperModel
from starlette.websockets import WebSocketState
from aura_security import issue_turn_credentials as create_turn_credentials
from aura_security import valid_client_id, valid_room_id, verify_device_token
from aura_privacy import TranscriptConsentGate
from aura_asr_quality import transcription_needs_repeat

LOG = logging.getLogger("aura.voip")
logging.basicConfig(level=os.getenv("AURA_LOG_LEVEL", "WARNING").upper(), format="%(asctime)s %(levelname)s %(message)s")

MAX_SIGNAL_CHARS = 96 * 1024
MAX_SDP_CHARS = 96 * 1024
MAX_UTTERANCE_SECONDS = 24
SAMPLE_RATE = 16_000
FRAME_MS = 20
FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS // 1000
FRAME_BYTES = FRAME_SAMPLES * 2


@dataclass(frozen=True)
class Settings:
    device_tokens: dict[str, str]
    turn_host: str
    turn_secret: str
    turn_realm: str
    turn_udp_port: int
    turn_tls_port: int
    turn_ttl_seconds: int
    asr_model_dir: Path
    asr_device: str
    asr_compute_type: str
    piper_bin: str
    piper_models: dict[str, Path]
    ollama_url: str
    ollama_model: str
    max_sessions: int

    @staticmethod
    def load() -> "Settings":
        try:
            tokens = json.loads(os.environ["AURA_DEVICE_TOKENS_JSON"])
            voices = json.loads(os.environ["AURA_PIPER_MODELS_JSON"])
        except (KeyError, json.JSONDecodeError) as exc:
            raise RuntimeError("AURA_DEVICE_TOKENS_JSON and AURA_PIPER_MODELS_JSON must be valid JSON environment variables.") from exc
        if not isinstance(tokens, dict) or not tokens or len(tokens) > 32:
            raise RuntimeError("One to 32 explicitly provisioned AURA device tokens are required.")
        for client_id, token in tokens.items():
            if not valid_client_id(client_id):
                raise RuntimeError("AURA device token map contains an invalid client ID.")
            if not isinstance(token, str) or len(token) < 64 or len(token) > 512:
                raise RuntimeError("Every AURA device token must be 64–512 characters.")
        if not isinstance(voices, dict) or not {"en", "bn", "hi"}.issubset(voices.keys()):
            raise RuntimeError("Local Piper voice paths for en, bn and hi are required. Voice weights are not downloaded by this service.")
        piper_models: dict[str, Path] = {}
        for language in ("en", "bn", "hi"):
            model = Path(str(voices[language])).resolve()
            if not model.is_file() or not Path(str(model) + ".json").is_file():
                raise RuntimeError(f"Piper model or companion JSON is missing for language {language}: {model}")
            piper_models[language] = model
        turn_host = os.environ.get("TURN_PUBLIC_HOST", "").strip()
        turn_secret = os.environ.get("TURN_SHARED_SECRET", "")
        if not turn_host or len(turn_secret) < 32:
            raise RuntimeError("TURN_PUBLIC_HOST and a 32+ character TURN_SHARED_SECRET are required for owner-controlled media relay.")
        from aura_security import valid_public_host
        if not valid_public_host(turn_host):
            raise RuntimeError("TURN_PUBLIC_HOST must be a DNS hostname or IP address only, without scheme, path or port.")
        asr_path = Path(os.getenv("AURA_ASR_MODEL_DIR", "/models/whisper")).resolve()
        if not asr_path.is_dir() or not any(asr_path.iterdir()):
            raise RuntimeError("A locally provisioned faster-whisper model directory is required. Downloads are disabled.")
        ollama_url = os.getenv("AURA_OLLAMA_URL", "http://ollama:11434").rstrip("/")
        parsed = urlparse(ollama_url)
        if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "ollama", "host.docker.internal"}:
            raise RuntimeError("AURA_OLLAMA_URL must target the local Ollama host or the private Docker service named 'ollama'.")
        udp_port = int(os.getenv("TURN_UDP_PORT", "3478"))
        tls_port = int(os.getenv("TURN_TLS_PORT", "5349"))
        if not (1 <= udp_port <= 65535 and 1 <= tls_port <= 65535):
            raise RuntimeError("TURN_UDP_PORT and TURN_TLS_PORT must be valid port numbers.")
        return Settings(
            device_tokens=tokens,
            turn_host=turn_host,
            turn_secret=turn_secret,
            turn_realm=os.getenv("TURN_REALM", turn_host),
            turn_udp_port=udp_port,
            turn_tls_port=tls_port,
            turn_ttl_seconds=max(300, min(3600, int(os.getenv("TURN_CREDENTIAL_TTL_SECONDS", "900")))),
            asr_model_dir=asr_path,
            asr_device=os.getenv("AURA_ASR_DEVICE", "cpu"),
            asr_compute_type=os.getenv("AURA_ASR_COMPUTE_TYPE", "int8"),
            piper_bin=os.getenv("AURA_PIPER_BIN", "piper"),
            piper_models=piper_models,
            ollama_url=ollama_url,
            ollama_model=os.getenv("AURA_OLLAMA_MODEL", "qwen2.5:3b"),
            max_sessions=max(1, min(4, int(os.getenv("AURA_MAX_SESSIONS", "1")))),
        )


class QueueAudioTrack(AudioStreamTrack):
    """Monophonic 48 kHz output track. Audio remains in memory and is never written as a call recording."""

    kind = "audio"

    def __init__(self) -> None:
        super().__init__()
        self._frames: asyncio.Queue[np.ndarray] = asyncio.Queue(maxsize=3_000)
        self._pts = 0
        self._closed = False
        self._generation = 0

    @property
    def generation(self) -> int:
        return self._generation

    async def enqueue_pcm48(self, samples: np.ndarray, expected_generation: int) -> bool:
        pcm = np.asarray(samples, dtype=np.int16).reshape(-1)
        for offset in range(0, len(pcm), 960):
            if self._closed or expected_generation != self._generation:
                return False
            frame = np.zeros(960, dtype=np.int16)
            portion = pcm[offset:offset + 960]
            frame[:len(portion)] = portion
            await self._frames.put(frame)
        return True

    def interrupt(self) -> None:
        self._generation += 1
        while True:
            try:
                self._frames.get_nowait()
            except asyncio.QueueEmpty:
                return

    async def recv(self) -> AudioFrame:
        if self._closed:
            raise asyncio.CancelledError
        await asyncio.sleep(0.020)
        try:
            pcm = self._frames.get_nowait()
        except asyncio.QueueEmpty:
            pcm = np.zeros(960, dtype=np.int16)
        frame = AudioFrame.from_ndarray(np.ascontiguousarray(pcm.reshape(1, -1)), format="s16", layout="mono")
        frame.sample_rate = 48_000
        frame.pts = self._pts
        frame.time_base = Fraction(1, 48_000)
        self._pts += 960
        return frame

    def stop(self) -> None:
        self._closed = True
        super().stop()


@dataclass
class CallSession:
    websocket: WebSocket
    client_id: str
    room_id: str
    preferred_language: str
    peer: RTCPeerConnection | None = None
    output_track: QueueAudioTrack | None = None
    audio_task: asyncio.Task[None] | None = None
    processing_task: asyncio.Task[None] | None = None
    greeting_task: asyncio.Task[None] | None = None
    utterance_queue: asyncio.Queue[bytes] = field(default_factory=lambda: asyncio.Queue(maxsize=2))
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    history: list[dict[str, str]] = field(default_factory=list)
    selected_language: str = "en"
    ended: bool = False
    greeting_sent: bool = False
    transcript_consent_decided: bool = False
    transcript_consent: bool = False
    consent_gate: TranscriptConsentGate = field(default_factory=TranscriptConsentGate)
    greeting_text: str = ""

    async def send(self, message_type: str, payload: dict[str, Any] | None = None, **extra: Any) -> None:
        if self.websocket.client_state != WebSocketState.CONNECTED:
            return
        message: dict[str, Any] = {"type": message_type, "room": self.room_id}
        if payload is not None:
            message["payload"] = payload
        message.update(extra)
        async with self.send_lock:
            if self.websocket.client_state == WebSocketState.CONNECTED:
                await self.websocket.send_json(message)


class LocalAIAgent:
    def __init__(self, settings: Settings, asr: WhisperModel) -> None:
        self.settings = settings
        self.asr = asr

    async def transcribe(self, pcm16: bytes, preferred_language: str) -> tuple[str, str, float, bool]:
        audio = np.frombuffer(pcm16, dtype=np.int16).astype(np.float32) / 32768.0
        def run() -> tuple[str, str, float, bool]:
            segments, info = self.asr.transcribe(
                audio,
                language=preferred_language if preferred_language in {"en", "bn", "hi"} else None,
                beam_size=3,
                vad_filter=False,
                condition_on_previous_text=False,
                temperature=0.0,
            )
            segment_list = list(segments)
            text = " ".join(segment.text.strip() for segment in segment_list if segment.text.strip()).strip()
            average_logprob = (
                sum(float(segment.avg_logprob) for segment in segment_list) / len(segment_list)
                if segment_list else float("-inf")
            )
            average_no_speech = (
                sum(float(segment.no_speech_prob) for segment in segment_list) / len(segment_list)
                if segment_list else 1.0
            )
            max_compression = max(
                (float(segment.compression_ratio) for segment in segment_list),
                default=float("inf"),
            )
            needs_repeat = transcription_needs_repeat(
                text, average_logprob, average_no_speech, max_compression
            )
            # Language probability estimates language identification, not word accuracy.
            language_probability = float(getattr(info, "language_probability", 0.0))
            return text, (info.language or "en").lower(), language_probability, needs_repeat
        return await asyncio.to_thread(run)

    async def respond(self, session: CallSession, caller_text: str, language: str) -> str:
        session.selected_language = language if language in {"en", "bn", "hi"} else "en"
        system = (
            "You are AURA, Diganta's personal AI telephone assistant. You are speaking directly to a caller. "
            "Identify yourself honestly as an AI assistant. Be courteous, natural, concise and professional. "
            "The owner is unavailable unless the owner explicitly tells you otherwise. Never invent the owner's "
            "location, schedule, availability, relationships, personal details, promises or commitments. Never "
            "reveal private data, secrets, OTPs, credentials, financial details or private messages. If you do not "
            "know something, say so and offer to pass a message. Ask the caller's name and reason for calling when "
            "appropriate. Collect only the minimum details needed for a callback or appointment request. Do not "
            "claim that an appointment was booked, a callback was placed, a payment was made, or any external "
            "action was completed. You have no tools and cannot call ordinary phone numbers. For emergencies, "
            "tell the caller to contact local emergency services directly. Respond in the caller's detected language. "
            "Supported response languages are English, Bengali and Hindi. If language is unclear, politely ask the "
            "caller to repeat in one of those languages. Keep the reply under four short sentences."
        )
        session.history.append({"role": "user", "content": caller_text[:2_000]})
        if len(session.history) > 24:
            del session.history[:-24]
        messages = [{"role": "system", "content": system}]
        messages.extend(session.history[-10:])
        payload = json.dumps({
            "model": self.settings.ollama_model,
            "messages": messages,
            "stream": False,
            "options": {"temperature": 0.25, "top_p": 0.8, "num_predict": 180},
        }).encode("utf-8")

        def call_local_model() -> str:
            from urllib.request import Request, urlopen
            request = Request(
                self.settings.ollama_url + "/api/chat",
                data=payload,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urlopen(request, timeout=60) as response:
                data = response.read(128 * 1024 + 1)
            if len(data) > 128 * 1024:
                raise RuntimeError("Local language model response exceeded the limit.")
            parsed = json.loads(data.decode("utf-8"))
            content = parsed.get("message", {}).get("content", "")
            if not isinstance(content, str) or not content.strip():
                raise RuntimeError("Local language model returned an empty reply.")
            return content.strip()[:2_000]

        try:
            response = await asyncio.to_thread(call_local_model)
        except Exception:
            LOG.warning("Local LLM request failed for session %s", session.room_id[:8])
            response = {
                "en": "I am having a temporary local processing problem. I can still take your message and pass it to Diganta.",
                "bn": "আমার স্থানীয় AI প্রক্রিয়াকরণে সাময়িক সমস্যা হচ্ছে। তবুও আমি আপনার বার্তাটি নিয়ে Diganta-কে জানাতে পারি।",
                "hi": "मेरे स्थानीय AI को अभी अस्थायी समस्या हो रही है। फिर भी मैं आपका संदेश लेकर Diganta तक पहुँचा सकती हूँ।",
            }.get(session.selected_language, "I am having a temporary local processing problem. I can still take your message.")
        session.history.append({"role": "assistant", "content": response[:2_000]})
        # Keep only the latest twelve exchanges; calls remain bounded in memory.
        if len(session.history) > 24:
            del session.history[:-24]
        return response

    async def summarize(self, session: CallSession) -> str | None:
        if not session.history:
            return None
        joined = "\n".join(f"{item['role']}: {item['content']}" for item in session.history[-16:])[:10_000]
        payload = json.dumps({
            "model": self.settings.ollama_model,
            "messages": [
                {"role": "system", "content": "Summarize this phone conversation in 2–4 factual sentences. State caller identity only if the caller actually gave it. Preserve dates, times, requests and callback details. Explicitly distinguish a request from a confirmed action. Do not invent facts. Return plain text only."},
                {"role": "user", "content": joined},
            ],
            "stream": False,
            "options": {"temperature": 0.1, "num_predict": 120},
        }).encode("utf-8")

        def call_local_model() -> str:
            from urllib.request import Request, urlopen
            request = Request(self.settings.ollama_url + "/api/chat", data=payload,
                              headers={"Content-Type": "application/json"}, method="POST")
            with urlopen(request, timeout=8) as response:
                data = response.read(64 * 1024 + 1)
            if len(data) > 64 * 1024:
                raise RuntimeError("Summary response too large")
            return str(json.loads(data.decode("utf-8")).get("message", {}).get("content", "")).strip()[:1_500]
        try:
            return await asyncio.to_thread(call_local_model)
        except Exception:
            LOG.warning("Local summary generation failed for session %s", session.room_id[:8])
            return None

    async def greeting(self, session: CallSession) -> None:
        language = session.preferred_language if session.preferred_language in {"en", "bn", "hi"} else "en"
        session.selected_language = language
        greetings = {
            "en": (
                "Hello, I am AURA, Diganta's AI assistant. He is busy at the moment. "
                "I process speech on Diganta's self-hosted AURA server and do not make an audio recording. "
                "May I save a written transcript and short summary in Diganta's encrypted AURA call history? "
                "Please say yes or no. If you decline, "
                "I can continue speaking with you, but I will not save the conversation text or summary."
            ),
            "bn": (
                "নমস্কার, আমি AURA, Diganta-র AI সহকারী। তিনি এই মুহূর্তে ব্যস্ত। আমি আপনার কথা "
                "Diganta-র নিজস্বভাবে পরিচালিত AURA সার্ভারে প্রক্রিয়া করি এবং কোনো অডিও রেকর্ড করি না। "
                "Diganta-র এনক্রিপ্ট করা AURA কল-ইতিহাসে লিখিত প্রতিলিপি ও সংক্ষিপ্ত সারাংশ সংরক্ষণ করতে "
                "কি আপনি সম্মতি দিচ্ছেন? দয়া করে হ্যাঁ বা না বলুন। "
                "সম্মতি না দিলে আমি কথা চালিয়ে যেতে পারি, কিন্তু কথার লেখা বা সারাংশ সংরক্ষণ করব না।"
            ),
            "hi": (
                "नमस्ते, मैं AURA हूँ, Diganta की AI सहायक। वे अभी व्यस्त हैं। मैं आपकी आवाज़ को "
                "Diganta के स्वयं-प्रबंधित AURA सर्वर पर प्रोसेस करती हूँ और कोई ऑडियो रिकॉर्डिंग नहीं बनाती। "
                "क्या मैं Diganta की एन्क्रिप्टेड AURA कॉल हिस्ट्री में लिखित ट्रांसक्रिप्ट और छोटा सारांश सहेज सकती हूँ? "
                "कृपया हाँ या नहीं कहें। मना करने पर "
                "मैं बात जारी रख सकती हूँ, लेकिन बातचीत का टेक्स्ट या सारांश सेव नहीं करूँगी।"
            ),
        }
        text = greetings[language]
        # Do not send or persist transcript text before the caller has explicitly opted in.
        session.greeting_text = text
        await self.speak(session, text, language)
        session.greeting_sent = True

    async def speak(self, session: CallSession, text: str, language: str) -> None:
        model_path = self.settings.piper_models.get(language, self.settings.piper_models["en"])
        if not shutil.which(self.settings.piper_bin) and not Path(self.settings.piper_bin).is_file():
            raise RuntimeError("Local Piper executable is not installed.")
        if session.output_track is None:
            raise RuntimeError("The WebRTC audio output track is unavailable.")
        generation = session.output_track.generation
        with tempfile.TemporaryDirectory(prefix="aura-tts-") as tmp:
            wav_path = Path(tmp) / "speech.wav"
            process = await asyncio.create_subprocess_exec(
                self.settings.piper_bin,
                "--model", str(model_path),
                "--output_file", str(wav_path),
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.PIPE,
            )
            try:
                _, stderr = await asyncio.wait_for(process.communicate(text[:1_200].encode("utf-8")), timeout=30)
            except asyncio.TimeoutError:
                if process.returncode is None:
                    with contextlib.suppress(ProcessLookupError):
                        process.kill()
                await process.wait()
                raise RuntimeError("Local Piper synthesis timed out.")
            except asyncio.CancelledError:
                if process.returncode is None:
                    with contextlib.suppress(ProcessLookupError):
                        process.kill()
                    await process.wait()
                raise
            if process.returncode != 0 or not wav_path.is_file():
                LOG.warning("Piper synthesis failed with exit code %s", process.returncode)
                raise RuntimeError("Local Piper could not synthesize this language.")
            pcm48 = await asyncio.to_thread(wav_to_pcm48, wav_path)
            await session.output_track.enqueue_pcm48(pcm48, generation)


SETTINGS: Settings | None = None
ASR: WhisperModel | None = None
AI: LocalAIAgent | None = None
SESSIONS = asyncio.Semaphore(1)
ACTIVE_ROOMS: set[str] = set()


def wav_to_pcm48(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        width = wav.getsampwidth()
        rate = wav.getframerate()
        if width != 2 or channels not in (1, 2) or rate < 8_000 or rate > 48_000:
            raise RuntimeError("Piper returned an unsupported WAV format.")
        raw = wav.readframes(wav.getnframes())
    data = np.frombuffer(raw, dtype="<i2")
    if channels == 2:
        data = data.reshape(-1, 2).mean(axis=1).astype(np.int16)
    input_frame = AudioFrame.from_ndarray(np.ascontiguousarray(data.reshape(1, -1)), format="s16", layout="mono")
    input_frame.sample_rate = rate
    resampler = av.AudioResampler(format="s16", layout="mono", rate=48_000)
    output = resampler.resample(input_frame)
    if not isinstance(output, list):
        output = [output]
    chunks = [np.asarray(frame.to_ndarray()).reshape(-1).astype(np.int16) for frame in output]
    return np.concatenate(chunks) if chunks else np.zeros(0, dtype=np.int16)


def issue_turn_credentials(settings: Settings, client_id: str) -> dict[str, Any]:
    return create_turn_credentials(
        public_host=settings.turn_host,
        shared_secret=settings.turn_secret,
        client_id=client_id,
        udp_port=settings.turn_udp_port,
        tls_port=settings.turn_tls_port,
        ttl_seconds=settings.turn_ttl_seconds,
    )


def audio_frame_to_16k(frame: AudioFrame, resampler: av.AudioResampler) -> bytes:
    converted = resampler.resample(frame)
    if not isinstance(converted, list):
        converted = [converted]
    chunks: list[bytes] = []
    for item in converted:
        pcm = np.asarray(item.to_ndarray()).reshape(-1).astype(np.int16, copy=False)
        chunks.append(pcm.tobytes())
    return b"".join(chunks)


async def process_utterance(session: CallSession, data: bytes) -> None:
    if len(data) < FRAME_BYTES * 8 or len(data) > FRAME_BYTES * 60 * MAX_UTTERANCE_SECONDS:
        return
    assert AI is not None
    text, language, language_probability, needs_repeat = await AI.transcribe(data, session.preferred_language)
    text = text.strip()[:2_000]
    if not text:
        needs_repeat = True
    if language not in {"en", "bn", "hi"} or language_probability < 0.42 or needs_repeat:
        # Preserve a supported detected language for the repeat prompt. A low-confidence
        # Bengali/Hindi utterance must not be answered in English just because the user
        # selected automatic language detection.
        retry_language = language if language in {"en", "bn", "hi"} else (
            session.preferred_language if session.preferred_language in {"en", "bn", "hi"} else "en"
        )
        language = retry_language
        prompt = {
            "en": "I did not understand that clearly. Could you please repeat it a little more slowly?",
            "bn": "আমি কথাটি স্পষ্টভাবে বুঝতে পারিনি। অনুগ্রহ করে একটু ধীরে আবার বলবেন?",
            "hi": "मैं आपकी बात स्पष्ट रूप से नहीं समझ पाई। कृपया थोड़ा धीरे दोबारा कहेंगे?",
        }[language]
        if session.transcript_consent:
            await session.send("transcript", {"speaker": "aura", "text": prompt, "language": language})
        try:
            await AI.speak(session, prompt, language)
        except Exception:
            await session.send("status", {"message": "Local voice retry prompt failed; no external TTS was used."})
        return

    if not session.transcript_consent_decided:
        consent = session.consent_gate.submit(text, language)
        if not session.consent_gate.decided:
            prompt = {
                "en": "I could not identify a clear yes or no. Would you like me to save a written transcript and summary?",
                "bn": "আপনার হ্যাঁ বা না স্পষ্ট বুঝতে পারিনি। লিখিত প্রতিলিপি ও সারাংশ সংরক্ষণে কি আপনি সম্মতি দিচ্ছেন?",
                "hi": "मैं आपका स्पष्ट हाँ या नहीं नहीं समझ पाई। क्या आप लिखित ट्रांसक्रिप्ट और सारांश सहेजने की अनुमति देते हैं?",
            }[language]
            await AI.speak(session, prompt, language)
            return
        # A clear no, or two ambiguous answers, always disables transcript retention.
        session.transcript_consent_decided = True
        session.transcript_consent = consent is True
        consent = session.transcript_consent
        if not consent and session.consent_gate.attempts >= session.consent_gate.max_ambiguous_answers:
            await session.send("status", {"message": "Transcript consent was unclear twice; retention is disabled for this call."})
        if consent:
            await session.send("transcript", {"speaker": "aura", "text": session.greeting_text, "language": language})
            await session.send("transcript", {"speaker": "caller", "text": text, "language": language})
            acknowledgement = {
                "en": "Thank you. I will save the written transcript and a short summary in Diganta's encrypted AURA call history. No audio recording is made. How may I help you?",
                "bn": "ধন্যবাদ। আমি লিখিত প্রতিলিপি ও সংক্ষিপ্ত সারাংশ Diganta-র এনক্রিপ্ট করা AURA কল-ইতিহাসে সংরক্ষণ করব। কোনো অডিও রেকর্ড করা হবে না। কীভাবে সাহায্য করতে পারি?",
                "hi": "धन्यवाद। मैं लिखित ट्रांसक्रिप्ट और छोटा सारांश Diganta की एन्क्रिप्टेड AURA कॉल हिस्ट्री में सहेजूँगी। कोई ऑडियो रिकॉर्डिंग नहीं होगी। मैं आपकी कैसे मदद करूँ?",
            }[language]
            await session.send("transcript", {"speaker": "aura", "text": acknowledgement, "language": language})
            await AI.speak(session, acknowledgement, language)
        else:
            acknowledgement = {
                "en": "Understood. I will not save a transcript or summary. We can continue, but I will not retain the details after this call. How may I help?",
                "bn": "বুঝেছি। আমি কোনো লিখিত প্রতিলিপি বা সারাংশ সংরক্ষণ করব না। আমরা কথা চালিয়ে যেতে পারি, তবে কলের পরে বিস্তারিত রাখব না। কীভাবে সাহায্য করতে পারি?",
                "hi": "समझ गई। मैं कोई ट्रांसक्रिप्ट या सारांश नहीं सहेजूँगी। हम बात जारी रख सकते हैं, लेकिन कॉल के बाद विवरण सुरक्षित नहीं रखूँगी। मैं आपकी कैसे मदद करूँ?",
            }[language]
            await session.send("status", {"message": "Transcript and summary retention are disabled for this call. Only call metadata will be saved."})
            await AI.speak(session, acknowledgement, language)
        return

    if session.transcript_consent:
        await session.send("transcript", {"speaker": "caller", "text": text, "language": language})
    response = await AI.respond(session, text, language)
    if session.transcript_consent:
        await session.send("transcript", {"speaker": "aura", "text": response, "language": language})
    try:
        await AI.speak(session, response, language)
    except Exception:
        # Never fail over to an online voice provider. A clear local-only status is returned.
        await session.send("status", {"message": "Local speech synthesis failed. No external speech service was contacted."})


async def process_utterance_queue(session: CallSession) -> None:
    while not session.ended:
        data = await session.utterance_queue.get()
        try:
            await process_utterance(session, data)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            LOG.warning("Local speech pipeline failed for session %s: %s", session.room_id[:8], type(exc).__name__)
            await session.send("status", {"message": "A local speech/AI component failed. No external AI service was contacted."})
        finally:
            session.utterance_queue.task_done()


async def consume_audio(session: CallSession, track: Any) -> None:
    vad = webrtcvad.Vad(2)
    resampler = av.AudioResampler(format="s16", layout="mono", rate=SAMPLE_RATE)
    carry = bytearray()
    preroll: deque[bytes] = deque(maxlen=10)
    utterance = bytearray()
    active = False
    silent_frames = 0
    max_frames = MAX_UTTERANCE_SECONDS * 1000 // FRAME_MS
    while not session.ended:
        try:
            frame = await track.recv()
        except Exception:
            # Preserve the final speech segment when the remote peer closes its
            # audio track before the 640 ms trailing-silence boundary is reached.
            if active and len(utterance) >= FRAME_BYTES * 8:
                try:
                    session.utterance_queue.put_nowait(bytes(utterance))
                except asyncio.QueueFull:
                    await session.send("status", {"message": "The local AI queue was full when the caller ended the audio stream; the final partial utterance was not retained."})
            return
        carry.extend(audio_frame_to_16k(frame, resampler))
        while len(carry) >= FRAME_BYTES:
            chunk = bytes(carry[:FRAME_BYTES])
            del carry[:FRAME_BYTES]
            speech = vad.is_speech(chunk, SAMPLE_RATE)
            if speech:
                if not active:
                    active = True
                    utterance = bytearray(b"".join(preroll))
                    # Interrupt any queued TTS response on detected caller speech.
                    if session.output_track is not None:
                        session.output_track.interrupt()
                utterance.extend(chunk)
                silent_frames = 0
            elif active:
                utterance.extend(chunk)
                silent_frames += 1
                if silent_frames >= 32:
                    if len(utterance) >= FRAME_BYTES * 8:
                        try:
                            session.utterance_queue.put_nowait(bytes(utterance))
                        except asyncio.QueueFull:
                            await session.send("status", {"message": "The local AI is busy; one spoken turn was dropped rather than buffered indefinitely."})
                    active = False
                    silent_frames = 0
                    utterance.clear()
                    preroll.clear()
            else:
                preroll.append(chunk)
            if active and len(utterance) // FRAME_BYTES >= max_frames:
                try:
                    session.utterance_queue.put_nowait(bytes(utterance))
                except asyncio.QueueFull:
                    await session.send("status", {"message": "The local AI is busy; the maximum speech-segment queue is full."})
                active = False
                silent_frames = 0
                utterance.clear()
                preroll.clear()


async def authenticate(websocket: WebSocket, settings: Settings) -> tuple[str, str] | None:
    authorization = websocket.headers.get("authorization", "")
    client_id = websocket.headers.get("x-aura-client", "")
    if not authorization.startswith("Bearer "):
        return None
    supplied = authorization[7:]
    if not verify_device_token(client_id, supplied, settings.device_tokens):
        return None
    return client_id, settings.device_tokens[client_id]


app = FastAPI(title="AURA Self-Hosted VoIP AI", docs_url=None, redoc_url=None, openapi_url=None)


async def verify_local_ollama_model(settings: Settings) -> None:
    """Fail startup unless the configured model is already installed on local Ollama."""
    def check() -> None:
        from urllib.request import Request, urlopen
        payload = json.dumps({"model": settings.ollama_model}).encode("utf-8")
        request = Request(
            settings.ollama_url + "/api/show",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(request, timeout=15) as response:
            raw = response.read(128 * 1024 + 1)
        if len(raw) > 128 * 1024:
            raise RuntimeError("Local Ollama model metadata exceeded the configured limit.")
        parsed = json.loads(raw.decode("utf-8"))
        if not isinstance(parsed, dict):
            raise RuntimeError("Local Ollama returned invalid model metadata.")
    await asyncio.to_thread(check)


@app.on_event("startup")
async def startup() -> None:
    global SETTINGS, ASR, AI, SESSIONS
    SETTINGS = Settings.load()
    SESSIONS = asyncio.Semaphore(SETTINGS.max_sessions)
    await verify_local_ollama_model(SETTINGS)
    # This loads only the local path; faster-whisper is not permitted to download a model.
    ASR = WhisperModel(
        str(SETTINGS.asr_model_dir),
        device=SETTINGS.asr_device,
        compute_type=SETTINGS.asr_compute_type,
        local_files_only=True,
    )
    AI = LocalAIAgent(SETTINGS, ASR)
    if not shutil.which(SETTINGS.piper_bin) and not Path(SETTINGS.piper_bin).is_file():
        raise RuntimeError("Piper executable missing. Install the owner-selected local Piper runtime.")
    LOG.warning("AURA local AI initialized. No external ASR, LLM, TTS or telephony provider is configured.")


@app.get("/health")
async def health() -> dict[str, Any]:
    ready = SETTINGS is not None and AI is not None and ASR is not None
    return {"status": "ready" if ready else "not_ready", "telephony": "self-hosted-webrtc-only", "external_ai": False}


@app.websocket("/ws/signaling")
async def signaling(websocket: WebSocket) -> None:
    settings = SETTINGS
    if settings is None or AI is None:
        await websocket.close(code=1013, reason="AURA local AI server is not ready")
        return
    auth = await authenticate(websocket, settings)
    if auth is None:
        await websocket.close(code=4401, reason="Invalid AURA device credentials")
        return
    client_id, _ = auth
    await websocket.accept()
    session: CallSession | None = None
    acquired = False
    try:
        first = await asyncio.wait_for(websocket.receive_text(), timeout=15)
        if len(first) > MAX_SIGNAL_CHARS:
            await websocket.close(code=4400, reason="Message too large")
            return
        try:
            join = json.loads(first)
        except json.JSONDecodeError:
            await websocket.close(code=4400, reason="Invalid join message")
            return
        if not isinstance(join, dict) or join.get("type") != "join" or join.get("client_id") != client_id:
            await websocket.close(code=4400, reason="Invalid join message")
            return
        room_id = str(join.get("room", ""))
        if not valid_room_id(room_id) or room_id in ACTIVE_ROOMS:
            await websocket.close(code=4409, reason="Invalid or occupied room")
            return
        role = str(join.get("role", ""))
        if role != "caller":
            await websocket.close(code=4403, reason="Only the AURA app caller role is supported by this AI endpoint")
            return
        preferred = str(join.get("preferred_language", "auto")).lower()
        if preferred not in {"auto", "en", "bn", "hi"}:
            preferred = "auto"
        try:
            await asyncio.wait_for(SESSIONS.acquire(), timeout=5.0)
        except asyncio.TimeoutError:
            await websocket.close(code=4429, reason="AURA local AI is busy. Please try again shortly.")
            return
        acquired = True
        # Re-check after waiting for the semaphore so two simultaneous joins
        # cannot claim the same room while one of them is queued.
        if room_id in ACTIVE_ROOMS:
            await websocket.close(code=4409, reason="Room became occupied while waiting")
            return
        ACTIVE_ROOMS.add(room_id)
        session = CallSession(websocket, client_id, room_id, preferred)
        relay = issue_turn_credentials(settings, client_id)
        await session.send("joined", {"ice_servers": [relay], "ai_endpoint": "aura-local-ai"}, ice_servers=[relay])

        while True:
            text = await websocket.receive_text()
            if len(text) > MAX_SIGNAL_CHARS:
                await session.send("error", {"message": "Signaling message too large."})
                break
            try:
                message = json.loads(text)
            except json.JSONDecodeError:
                await session.send("error", {"message": "Malformed signaling message."})
                continue
            if not isinstance(message, dict):
                await session.send("error", {"message": "Malformed signaling message."})
                continue
            if message.get("room") != room_id:
                await session.send("error", {"message": "Room mismatch."})
                continue
            kind = message.get("type")
            payload = message.get("payload") or {}
            if not isinstance(payload, dict):
                await session.send("error", {"message": "Malformed signaling payload."})
                continue
            if kind == "offer":
                if session.peer is not None:
                    await session.send("error", {"message": "This session already has a WebRTC peer."})
                    continue
                sdp = payload.get("sdp")
                if not isinstance(sdp, str) or not sdp.startswith("v=") or len(sdp) > MAX_SDP_CHARS:
                    await session.send("error", {"message": "Invalid WebRTC offer."})
                    continue
                session.output_track = QueueAudioTrack()
                ice_url = relay["urls"]
                peer_ice = RTCIceServer(urls=ice_url, username=relay["username"], credential=relay["credential"])
                peer_config = RTCConfiguration(iceServers=[peer_ice], iceTransportPolicy="relay")
                pc = RTCPeerConnection(configuration=peer_config)
                session.peer = pc
                pc.addTrack(session.output_track)

                @pc.on("track")
                def on_track(track: Any) -> None:
                    if track.kind == "audio" and session is not None:
                        session.processing_task = asyncio.create_task(process_utterance_queue(session))
                        session.audio_task = asyncio.create_task(consume_audio(session, track))

                @pc.on("connectionstatechange")
                async def on_connection_state() -> None:
                    if pc.connectionState == "connected" and session is not None and not session.greeting_sent:
                        try:
                            await session.send("status", {"message": "Connected to your local AURA AI. No call audio is being recorded."})
                            if AI is not None:
                                async def send_greeting() -> None:
                                    try:
                                        await AI.greeting(session)
                                    except asyncio.CancelledError:
                                        raise
                                    except Exception:
                                        LOG.warning("AURA local greeting failed for session %s", room_id[:8])
                                        await session.send("status", {"message": "The local greeting voice failed. No external TTS service was contacted."})
                                session.greeting_task = asyncio.create_task(send_greeting())
                        except Exception:
                            LOG.warning("AURA greeting could not be scheduled for session %s", room_id[:8])
                    elif pc.connectionState in {"failed", "closed"} and session is not None:
                        await session.send("status", {"message": "The local AI media connection ended."})

                await pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type="offer"))
                answer = await pc.createAnswer()
                await pc.setLocalDescription(answer)
                await session.send("answer", {"sdp": pc.localDescription.sdp})
            elif kind == "hangup":
                if session.processing_task is not None and not session.processing_task.done():
                    try:
                        await asyncio.wait_for(session.utterance_queue.join(), timeout=4.0)
                    except asyncio.TimeoutError:
                        session.processing_task.cancel()
                        try:
                            await session.processing_task
                        except asyncio.CancelledError:
                            pass
                if session.history and session.transcript_consent:
                    try:
                        summary = await asyncio.wait_for(AI.summarize(session), timeout=6.0)
                    except asyncio.TimeoutError:
                        summary = None
                    if summary:
                        await session.send("call_summary", {"summary": summary})
                    else:
                        await session.send("status", {"message": "The local summary was unavailable; the transcript will still be stored on your device."})
                end_reason = (
                    "AURA AI session ended. The consented transcript is stored locally on your device."
                    if session.transcript_consent else
                    "AURA AI session ended. Transcript retention was not enabled; only call metadata is stored locally."
                )
                await session.send("call_ended", {"reason": end_reason})
                break
            else:
                await session.send("error", {"message": "Unsupported signaling message type."})
    except WebSocketDisconnect:
        pass
    except asyncio.TimeoutError:
        LOG.warning("AURA signaling join or message timed out")
    except Exception as exc:
        LOG.warning("AURA signaling session failed: %s", type(exc).__name__)
        if session is not None:
            try:
                await session.send("error", {"message": "The local AURA session failed. Check server logs and local model availability."})
            except Exception:
                pass
    finally:
        if session is not None:
            session.ended = True
            for task in (session.greeting_task, session.audio_task, session.processing_task):
                if task is not None and not task.done():
                    task.cancel()
                    try:
                        await task
                    except (asyncio.CancelledError, Exception):
                        pass
            if session.output_track is not None:
                session.output_track.stop()
            if session.peer is not None:
                try:
                    await session.peer.close()
                except Exception:
                    LOG.warning("WebRTC peer cleanup failed for session %s", session.room_id[:8])
            ACTIVE_ROOMS.discard(session.room_id)
        if acquired:
            SESSIONS.release()
        if websocket.application_state == WebSocketState.CONNECTED:
            try:
                await websocket.close()
            except Exception:
                pass
