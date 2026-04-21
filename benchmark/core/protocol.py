"""消息协议定义 — 所有 JSON 消息的构建和解析。"""

import json
import uuid
import time


def _trace_id() -> str:
    return uuid.uuid4().hex


def build_invoke_message(
    ak: str,
    user_id: str,
    welink_session_id: str,
    tool_session_id: str,
    text: str,
    assistant_account: str,
    sender_account: str,
    message_id: str | None = None,
    trace_id: str | None = None,
) -> str:
    return json.dumps({
        "type": "invoke",
        "ak": ak,
        "source": "skill-server",
        "userId": user_id,
        "welinkSessionId": welink_session_id,
        "action": "chat",
        "payload": {
            "text": text,
            "toolSessionId": tool_session_id,
            "assistantAccount": assistant_account,
            "sendUserAccount": sender_account,
            "messageId": message_id or str(int(time.time() * 1000)),
        },
        "traceId": trace_id or _trace_id(),
    })


def build_tool_done_message(
    ak: str,
    tool_session_id: str,
    welink_session_id: str,
    trace_id: str | None = None,
) -> str:
    return json.dumps({
        "type": "tool_done",
        "ak": ak,
        "toolSessionId": tool_session_id,
        "welinkSessionId": welink_session_id,
        "usage": {"input_tokens": 100, "output_tokens": 50},
        "traceId": trace_id or _trace_id(),
    })


def build_register_message(ak: str, index: int = 0) -> str:
    return json.dumps({
        "type": "register",
        "ak": ak,
        "deviceName": f"benchmark-agent-{index}",
        "macAddress": f"00:00:00:00:00:{index:02d}",
        "os": "linux",
        "toolType": "benchmark",
        "toolVersion": "1.0.0",
    })


def build_heartbeat_message(ak: str) -> str:
    return json.dumps({"type": "heartbeat", "ak": ak})


def build_inbound_request_body(
    session_id: str,
    assistant_account: str,
    sender_account: str,
    content: str = "benchmark test message",
) -> dict:
    return {
        "businessDomain": "im",
        "sessionType": "direct",
        "sessionId": session_id,
        "assistantAccount": assistant_account,
        "senderUserAccount": sender_account,
        "content": content,
        "msgType": "text",
    }


def parse_message(raw: str) -> dict:
    return json.loads(raw)
