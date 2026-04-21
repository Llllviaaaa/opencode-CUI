"""Mock MiniApp Client — 模拟前端 MiniApp 连接 SS。

连接 /ws/skill/stream，接收推送，识别 step.done/tool_done 类型消息后
通知对应会话的同步循环可以继续。
"""

import asyncio
import json
import logging
import time

import websockets

from core.auth import build_miniapp_headers
from core.protocol import parse_message

logger = logging.getLogger(__name__)


class MockMiniAppClient:
    def __init__(self, ss_ws_url: str, user_id: str):
        self.ss_ws_url = ss_ws_url
        self.user_id = user_id
        self._ws = None
        self._listen_task: asyncio.Task | None = None
        # welink_session_id -> Future for sync waiting
        self._pending: dict[str, asyncio.Future] = {}
        self._lock = asyncio.Lock()
        self._connected = asyncio.Event()

    async def connect(self):
        headers = build_miniapp_headers(self.user_id)
        self._ws = await websockets.connect(
            self.ss_ws_url,
            additional_headers=headers,
            open_timeout=10,
        )
        self._listen_task = asyncio.create_task(self._listen_loop())
        self._connected.set()

    async def close(self):
        if self._listen_task:
            self._listen_task.cancel()
        if self._ws:
            await self._ws.close()

    async def wait_tool_done(
        self, welink_session_id: str, timeout: float = 30.0
    ) -> float:
        """Wait for SS to push tool_done message, return the receive timestamp."""
        loop = asyncio.get_event_loop()
        fut = loop.create_future()

        async with self._lock:
            self._pending[welink_session_id] = fut

        try:
            recv_ts = await asyncio.wait_for(fut, timeout)
            return recv_ts
        finally:
            async with self._lock:
                self._pending.pop(welink_session_id, None)

    async def _listen_loop(self):
        try:
            async for raw in self._ws:
                msg = parse_message(raw)
                msg_type = msg.get("type", "")

                # SS pushes tool_done as "step.done", "tool_done", or "text.done"
                if msg_type in ("step.done", "tool_done", "text.done"):
                    recv_ts = time.monotonic()
                    wsid = msg.get("welinkSessionId", "")
                    async with self._lock:
                        fut = self._pending.get(wsid)
                    if fut and not fut.done():
                        fut.set_result(recv_ts)
        except (websockets.ConnectionClosed, asyncio.CancelledError):
            pass
