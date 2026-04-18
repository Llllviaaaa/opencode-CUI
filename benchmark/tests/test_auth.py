import base64
import json
import hmac
import hashlib
from core.auth import (
    build_agent_auth_protocol,
    build_skill_auth_protocol,
    build_miniapp_headers,
)


def test_build_agent_auth_protocol_format():
    proto = build_agent_auth_protocol("ak1", "sk1")
    assert proto.startswith("auth.")
    payload_b64 = proto[5:]
    payload = json.loads(base64.b64decode(payload_b64))
    assert payload["ak"] == "ak1"
    assert "ts" in payload
    assert "nonce" in payload
    assert "sign" in payload


def test_build_agent_auth_protocol_signature():
    proto = build_agent_auth_protocol("ak1", "sk1")
    payload = json.loads(base64.b64decode(proto[5:]))
    sign_str = payload["ak"] + payload["ts"] + payload["nonce"]
    expected = hmac.new(
        "sk1".encode(), sign_str.encode(), hashlib.sha256
    ).hexdigest()
    assert payload["sign"] == expected


def test_build_skill_auth_protocol():
    proto = build_skill_auth_protocol("token1", "skill-server", "inst-1")
    assert proto.startswith("auth.")
    payload = json.loads(base64.b64decode(proto[5:]))
    assert payload["token"] == "token1"
    assert payload["source"] == "skill-server"
    assert payload["instanceId"] == "inst-1"


def test_build_miniapp_headers():
    headers = build_miniapp_headers("user_123")
    assert headers["Cookie"] == "userId=user_123"
