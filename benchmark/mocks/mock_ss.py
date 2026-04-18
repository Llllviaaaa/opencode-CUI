"""Mock Skill Server — 模拟 SS 的 GatewayRelayService。

建立连接池到 GW 的 /ws/skill，发送 invoke 并等待 tool_done。
支持多会话复用连接池，每个会话独立跑同步循环。
"""

import asyncio
import json
import logging
from collections import defaultdict

import websockets

from core.auth import build_skill_auth_protocol
from core.protocol import build_invoke_message, parse_message

logger = logging.getLogger(__name__)


class MockSS:
    def __init__(
        self,
        gw_skill_url: str,
        internal_token: str,
        connection_count: int = 8,
        instance_id: str = "mock-ss-1",
    ):
        self.gw_skill_url = gw_skill_url
        self.internal_token = internal_token
        self.connection_count = connection_count
        self.instance_id = instance_id
        self._connections: list = []
        self._listen_tasks: list[asyncio.Task] = []
        # tool_session_id -> Future for sync waiting
        self._pending: dict[str, asyncio.Future] = {}
        self._lock = asyncio.Lock()
        self._round_robin = 0

    async def connect(self):
        proto = build_skill_auth_protocol(
            self.internal_token, "skill-server", self.instance_id
        )
        # WebSocket subprotocol token must match RFC 2616 token syntax.
        # Replace '.' with '-' and strip base64 padding '=' to comply.
        proto_token = proto.replace(".", "-").replace("=", "")
        for i in range(self.connection_count):
            ws = await websockets.connect(
                self.gw_skill_url,
                subprotocols=[proto_token],
                open_timeout=10,
            )
            self._connections.append(ws)
            task = asyncio.create_task(self._listen_loop(ws))
            self._listen_tasks.append(task)
        logger.info(
            f"MockSS connected {self.connection_count} sessions to {self.gw_skill_url}"
        )

    async def close(self):
        for t in self._listen_tasks:
            t.cancel()
        for ws in self._connections:
            await ws.close()
        self._connections.clear()
        self._listen_tasks.clear()

    async def send_invoke_and_wait(
        self,
        ak: str,
        user_id: str,
        welink_session_id: str,
        tool_session_id: str,
        text: str = "benchmark",
        timeout: float = 30.0,
    ) -> dict:
        """Send invoke and synchronously wait for tool_done, return the tool_done message."""
        loop = asyncio.get_event_loop()
        fut = loop.create_future()

        async with self._lock:
            self._pending[tool_session_id] = fut

        msg = build_invoke_message(
            ak=ak,
            user_id=user_id,
            welink_session_id=welink_session_id,
            tool_session_id=tool_session_id,
            text=text,
            assistant_account="bench_bot",
            sender_account=f"bench_user_{user_id}",
        )

        # Round-robin connection selection
        ws = self._connections[self._round_robin % len(self._connections)]
        self._round_robin += 1

        await ws.send(msg)

        try:
            result = await asyncio.wait_for(fut, timeout)
            return result
        finally:
            async with self._lock:
                self._pending.pop(tool_session_id, None)

    async def _listen_loop(self, ws):
        try:
            async for raw in ws:
                msg = parse_message(raw)
                msg_type = msg.get("type")

                if msg_type == "tool_done":
                    tsid = msg.get("toolSessionId", "")
                    async with self._lock:
                        fut = self._pending.get(tsid)
                    if fut and not fut.done():
                        fut.set_result(msg)
        except (websockets.ConnectionClosed, asyncio.CancelledError):
            pass
