"""Pure security helpers for AURA's owner-operated TURN/signalling protocol."""
from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import re
import time
from typing import Mapping, Any

CLIENT_ID_RE = re.compile(r"^[A-Za-z0-9_-]{3,48}$")
ROOM_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
DNS_LABEL_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")


def valid_client_id(value: object) -> bool:
    return isinstance(value, str) and CLIENT_ID_RE.fullmatch(value) is not None


def valid_room_id(value: object) -> bool:
    return isinstance(value, str) and ROOM_ID_RE.fullmatch(value) is not None


def valid_public_host(value: object) -> bool:
    if not isinstance(value, str) or not value or len(value) > 253 or value.strip() != value:
        return False
    try:
        ipaddress.ip_address(value)
        return True
    except ValueError:
        if ":" in value or "/" in value or "@" in value or "?" in value or "#" in value:
            return False
        labels = value.rstrip(".").split(".")
        return bool(labels) and all(DNS_LABEL_RE.fullmatch(label) for label in labels)


def verify_device_token(client_id: str, supplied: str, configured: Mapping[str, str]) -> bool:
    if not valid_client_id(client_id) or not isinstance(supplied, str):
        return False
    expected = configured.get(client_id)
    return isinstance(expected, str) and hmac.compare_digest(supplied, expected)


def issue_turn_credentials(
    *,
    public_host: str,
    shared_secret: str,
    client_id: str,
    udp_port: int,
    tls_port: int,
    ttl_seconds: int,
    now_epoch_seconds: int | None = None,
) -> dict[str, Any]:
    """Issue coturn REST API credentials; caller provides owner-controlled config only."""
    if not valid_public_host(public_host):
        raise ValueError("TURN public host must be a DNS hostname or IP address only.")
    if len(shared_secret) < 32:
        raise ValueError("TURN shared secret must be at least 32 characters.")
    if not valid_client_id(client_id):
        raise ValueError("Invalid AURA device client ID.")
    if not (1 <= udp_port <= 65535 and 1 <= tls_port <= 65535):
        raise ValueError("TURN ports must be valid TCP/UDP port numbers.")
    if not (300 <= ttl_seconds <= 3600):
        raise ValueError("TURN credential TTL must be between 300 and 3600 seconds.")

    expiry = (int(time.time()) if now_epoch_seconds is None else int(now_epoch_seconds)) + ttl_seconds
    username = f"{expiry}:{client_id}"
    digest = hmac.new(shared_secret.encode("utf-8"), username.encode("utf-8"), hashlib.sha1).digest()
    credential = base64.b64encode(digest).decode("ascii")
    host = f"[{public_host}]" if ":" in public_host else public_host
    return {
        "urls": [
            f"turn:{host}:{udp_port}?transport=udp",
            f"turns:{host}:{tls_port}?transport=tcp",
        ],
        "username": username,
        "credential": credential,
        "expires_at": expiry,
    }
