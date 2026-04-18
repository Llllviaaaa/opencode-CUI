"""Mock Agent — 模拟 PCAgent 连接 GW。

完成 AK/SK 认证、register、心跳保活。
收到 invoke 后自动回复 tool_done。
"""

import asyncio
import logging

import websockets

from core.auth import build_agent_auth_protocol
from core.protocol import (
    build_register_message,
    build_heartbeat_message,
    build_tool_done_message,
    parse_message,
)

logger = logging.getLogger(__name__)


class MockAgent:
    def __init__(
        self,
        gw_url: str,
        ak: str,
        sk: str,
        index: int = 0,
        heartbeat_interval: float = 30.0,
    ):
        self.gw_url = gw_url
        self.ak = ak
        self.sk = sk
        self.index = index
        self.heartbeat_interval = heartbeat_interval
        self._ws = None
        self._heartbeat_task: asyncio.Task | None = None
        self._listen_task: asyncio.Task | None = None
        self._registered = asyncio.Event()
        self._stopped = False

    async def connect_and_register(self):
        proto = build_agent_auth_protocol(self.ak, self.sk)
        self._ws = await websockets.connect(
            self.gw_url,
            subprotocols=[proto],
            open_timeout=10,
        )
        # Send register
        reg_msg = build_register_message(self.ak, self.index)
        await self._ws.send(reg_msg)

        # Start listen and heartbeat
        self._listen_task = asyncio.create_task(self._listen_loop())
        self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())
        self._registered.set()
        logger.debug(f"MockAgent {self.ak} registered")

    async def wait_registered(self, timeout: float = 10.0):
        await asyncio.wait_for(self._registered.wait(), timeout)

    async def close(self):
        self._stopped = True
        if self._heartbeat_task:
            self._heartbeat_task.cancel()
        if self._listen_task:
            self._listen_task.cancel()
        if self._ws:
            await self._ws.close()

    async def _listen_loop(self):
        try:
            async for raw in self._ws:
                msg = parse_message(raw)
                msg_type = msg.get("type")

                if msg_type == "invoke":
                    payload = msg.get("payload", {})
                    reply = build_tool_done_message(
                        ak=self.ak,
                        tool_session_id=payload.get("toolSessionId", ""),
                        welink_session_id=msg.get("welinkSessionId", ""),
                        trace_id=msg.get("traceId", ""),
                    )
                    await self._ws.send(reply)
                # register_ok, heartbeat_ack etc. are ignored
        except (websockets.ConnectionClosed, asyncio.CancelledError):
            pass

    async def _heartbeat_loop(self):
        try:
            while not self._stopped:
                await asyncio.sleep(self.heartbeat_interval)
                if self._ws and not self._ws.closed:
                    hb = build_heartbeat_message(self.ak)
                    await self._ws.send(hb)
        except (websockets.ConnectionClosed, asyncio.CancelledError):
            pass
