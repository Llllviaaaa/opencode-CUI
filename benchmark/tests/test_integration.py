"""集成测试 — 验证 Mock 组件间的通信。"""

import asyncio
import pytest
from mocks.mock_gw import MockGW
from mocks.mock_ss import MockSS


@pytest.mark.asyncio
async def test_mock_ss_invoke_via_mock_gw():
    """MockSS → MockGW invoke → tool_done 往返。"""
    gw = MockGW(host="127.0.0.1", port=19081, reply_delay_ms=0)
    await gw.start()

    ss = MockSS(
        gw_skill_url="ws://127.0.0.1:19081/ws/skill",
        internal_token="test-token",
        connection_count=1,
    )
    await ss.connect()

    try:
        result = await ss.send_invoke_and_wait(
            ak="test_ak",
            user_id="test_user",
            welink_session_id="ws_1",
            tool_session_id="ts_1",
            timeout=5.0,
        )
        assert result["type"] == "tool_done"
        assert result["toolSessionId"] == "ts_1"
        assert gw.invoke_count == 1
    finally:
        await ss.close()
        await gw.stop()


@pytest.mark.asyncio
async def test_mock_ss_concurrent_invokes():
    """并发多个 invoke，验证各自独立收到 tool_done。"""
    gw = MockGW(host="127.0.0.1", port=19082, reply_delay_ms=0)
    await gw.start()

    ss = MockSS(
        gw_skill_url="ws://127.0.0.1:19082/ws/skill",
        internal_token="test-token",
        connection_count=2,
    )
    await ss.connect()

    try:
        tasks = [
            ss.send_invoke_and_wait(
                ak=f"ak_{i}",
                user_id=f"user_{i}",
                welink_session_id=f"ws_{i}",
                tool_session_id=f"ts_{i}",
                timeout=5.0,
            )
            for i in range(10)
        ]
        results = await asyncio.gather(*tasks)
        assert len(results) == 10
        for i, r in enumerate(results):
            assert r["type"] == "tool_done"
            assert r["toolSessionId"] == f"ts_{i}"
    finally:
        await ss.close()
        await gw.stop()
