#!/usr/bin/env python3
"""Generate a high-entropy per-device AURA VoIP bearer token."""
import argparse
import json
import secrets
import re

parser = argparse.ArgumentParser()
parser.add_argument("client_id", help="3–48 characters: letters, digits, _ or -")
parser.add_argument("--json", action="store_true", help="Print a JSON map suitable for AURA_DEVICE_TOKENS_JSON")
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_-]{3,48}", args.client_id):
    parser.error("client_id must contain 3–48 letters, digits, underscores or hyphens")
token = secrets.token_urlsafe(48)
print(json.dumps({args.client_id: token}) if args.json else token)
