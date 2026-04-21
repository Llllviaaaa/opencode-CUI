"""Mock Gateway — WebSocket Server，模拟 /ws/skill 端点。

SS 的 GatewayRelayService 会连接到此 Server。
收到 invoke 消息后自动回复 tool_done。
"""

import asyncio
import logging
from typing import Callable, Awaitable

import websockets
from websockets.legacy.server import WebSocketServerProtocol

from core.protocol import build_tool_done_message, parse_message

logger = logging.getLogger(__name__)


class MockGW:
    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = 8081,
        reply_delay_ms: int = 5,
        on_invoke: Callable[[dict], Awaitable[None]] | None = None,
    ):
        self.host = host
        self.port = port
        self.reply_delay_sec = reply_delay_ms / 1000.0
        self.on_invoke = on_invoke
        self._server = None
        self._connections: set[WebSocketServerProtocol] = set()
        self._invoke_count = 0

    async def start(self):
        self._server = await websockets.serve(
            self._handler,
            self.host,
            self.port,
            subprotocols=["auth"],
            process_request=self._process_request,
        )
        logger.info(f"MockGW listening on ws://{self.host}:{self.port}/ws/skill")

    async def stop(self):
        if self._server:
            self._server.close()
            await self._server.wait_closed()

    @property
    def invoke_count(self) -> int:
        return self._invoke_count

    async def _process_request(self, path: str, request_headers):
        # Accept all paths and auth without validation
        return None

    async def _handler(self, ws: WebSocketServerProtocol):
        self._connections.add(ws)
        try:
            async for raw in ws:
                msg = parse_message(raw)
                msg_type = msg.get("type")

                if msg_type == "invoke":
                    self._invoke_count += 1
                    if self.on_invoke:
                        await self.on_invoke(msg)

                    if self.reply_delay_sec > 0:
                        await asyncio.sleep(self.reply_delay_sec)

                    ak = msg.get("ak", "")
                    payload = msg.get("payload", {})
                    tool_session_id = payload.get("toolSessionId", "")
                    welink_session_id = msg.get("welinkSessionId", "")
                    trace_id = msg.get("traceId", "")

                    reply = build_tool_done_message(
                        ak=ak,
                        tool_session_id=tool_session_id,
                        welink_session_id=welink_session_id,
                        trace_id=trace_id,
                    )
                    await ws.send(reply)

        except websockets.ConnectionClosed:
            pass
        finally:
            self._connections.discard(ws)
