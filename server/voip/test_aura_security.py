from __future__ import annotations

import base64
import hashlib
import hmac
import unittest

from aura_security import (
    issue_turn_credentials,
    valid_client_id,
    valid_public_host,
    valid_room_id,
    verify_device_token,
)


class AuraSecurityTests(unittest.TestCase):
    def test_client_and_room_identifiers_are_strictly_bounded(self) -> None:
        self.assertTrue(valid_client_id("android-main_01"))
        self.assertFalse(valid_client_id("../admin"))
        self.assertFalse(valid_client_id("ab"))
        self.assertTrue(valid_room_id("aura-private-room_1"))
        self.assertFalse(valid_room_id("room with spaces"))
        self.assertFalse(valid_room_id("short"))

    def test_public_turn_host_rejects_url_injection(self) -> None:
        self.assertTrue(valid_public_host("turn.example.net"))
        self.assertTrue(valid_public_host("192.0.2.10"))
        self.assertTrue(valid_public_host("2001:db8::10"))
        self.assertFalse(valid_public_host("turn.example.net/path"))
        self.assertFalse(valid_public_host("https://turn.example.net"))
        self.assertFalse(valid_public_host("user@turn.example.net"))

    def test_device_token_is_bound_to_the_declared_client_id(self) -> None:
        tokens = {"android-main": "a" * 64}
        self.assertTrue(verify_device_token("android-main", "a" * 64, tokens))
        self.assertFalse(verify_device_token("android-main", "b" * 64, tokens))
        self.assertFalse(verify_device_token("unknown-device", "a" * 64, tokens))
        self.assertFalse(verify_device_token("../admin", "a" * 64, tokens))

    def test_turn_credential_uses_expiry_client_and_hmac_sha1(self) -> None:
        result = issue_turn_credentials(
            public_host="turn.example.net",
            shared_secret="s" * 64,
            client_id="android-main",
            udp_port=3478,
            tls_port=5349,
            ttl_seconds=900,
            now_epoch_seconds=1_700_000_000,
        )
        username = "1700000900:android-main"
        expected = base64.b64encode(
            hmac.new(b"s" * 64, username.encode("utf-8"), hashlib.sha1).digest()
        ).decode("ascii")
        self.assertEqual(username, result["username"])
        self.assertEqual(expected, result["credential"])
        self.assertEqual(1_700_000_900, result["expires_at"])
        self.assertEqual(
            ["turn:turn.example.net:3478?transport=udp", "turns:turn.example.net:5349?transport=tcp"],
            result["urls"],
        )

    def test_ipv6_turn_urls_are_bracketed(self) -> None:
        result = issue_turn_credentials(
            public_host="2001:db8::10",
            shared_secret="s" * 64,
            client_id="android-main",
            udp_port=3478,
            tls_port=5349,
            ttl_seconds=900,
            now_epoch_seconds=100,
        )
        self.assertEqual("turn:[2001:db8::10]:3478?transport=udp", result["urls"][0])

    def test_invalid_turn_configuration_is_rejected(self) -> None:
        common = dict(
            public_host="turn.example.net",
            shared_secret="s" * 64,
            client_id="android-main",
            udp_port=3478,
            tls_port=5349,
            ttl_seconds=900,
            now_epoch_seconds=100,
        )
        for overrides in (
            {"public_host": "turn.example.net/evil"},
            {"shared_secret": "short"},
            {"udp_port": 70000},
            {"ttl_seconds": 30},
        ):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                issue_turn_credentials(**(common | overrides))


if __name__ == "__main__":
    unittest.main()
