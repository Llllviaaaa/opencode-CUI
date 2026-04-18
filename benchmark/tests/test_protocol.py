import json
from core.protocol import (
    build_invoke_message,
    build_tool_done_message,
    build_register_message,
    build_heartbeat_message,
    build_inbound_request_body,
    parse_message,
)


def test_build_invoke_message():
    msg = build_invoke_message(
        ak="ak1",
        user_id="u1",
        welink_session_id="ws1",
        tool_session_id="ts1",
        text="hello",
        assistant_account="bot1",
        sender_account="sender1",
        message_id="m1",
        trace_id="t1",
    )
    data = json.loads(msg)
    assert data["type"] == "invoke"
    assert data["ak"] == "ak1"
    assert data["action"] == "chat"
    assert data["payload"]["toolSessionId"] == "ts1"
    assert data["payload"]["text"] == "hello"
    assert data["traceId"] == "t1"


def test_build_tool_done_message():
    msg = build_tool_done_message(
        ak="ak1",
        tool_session_id="ts1",
        welink_session_id="ws1",
        trace_id="t1",
    )
    data = json.loads(msg)
    assert data["type"] == "tool_done"
    assert data["ak"] == "ak1"
    assert data["toolSessionId"] == "ts1"
    assert data["usage"]["input_tokens"] == 100


def test_build_register_message():
    msg = build_register_message(ak="ak1", index=5)
    data = json.loads(msg)
    assert data["type"] == "register"
    assert data["ak"] == "ak1"
    assert data["macAddress"] == "00:00:00:00:00:05"


def test_build_heartbeat_message():
    msg = build_heartbeat_message(ak="ak1")
    data = json.loads(msg)
    assert data["type"] == "heartbeat"
    assert data["ak"] == "ak1"


def test_build_inbound_request_body():
    body = build_inbound_request_body(
        session_id="s1",
        assistant_account="bot1",
        sender_account="sender1",
        content="hi",
    )
    assert body["businessDomain"] == "im"
    assert body["sessionType"] == "direct"
    assert body["sessionId"] == "s1"
    assert body["msgType"] == "text"


def test_parse_message():
    raw = json.dumps({"type": "tool_done", "ak": "ak1", "toolSessionId": "ts1"})
    parsed = parse_message(raw)
    assert parsed["type"] == "tool_done"
    assert parsed["toolSessionId"] == "ts1"
