"""
External WebSocket Channel 端到端测试
验证新增的 /api/external/invoke REST 接口和 /ws/external/stream WebSocket 端点。

前提：
  1. python tools/mock-cloud-server.py (端口 9999)
  2. skill-server (端口 8082, 指向 mock)
  3. ai-gateway (端口 8081, 指向 mock)

用法: python tools/e2e-test-external-channel.py [--mock-only]
  --mock-only: 只测试 REST 校验（不需要 GW/Mock，但需要 SS）

依赖: pip install requests websocket-client
"""

import sys
import json
import time
import base64
import argparse
import os
import threading

import requests

try:
    import websocket
except ImportError:
    print("ERROR: websocket-client not installed. Run: pip install websocket-client")
    sys.exit(1)

MOCK_URL = os.environ.get("MOCK_URL", "http://localhost:9999")
SS_URL = os.environ.get("SS_URL", "http://localhost:8082")
GW_URL = os.environ.get("GW_URL", "http://localhost:8081")
IM_INBOUND_TOKEN = os.environ.get("IM_INBOUND_TOKEN", "e2e-test-token")
WS_URL = SS_URL.replace("http://", "ws://") + "/ws/external/stream"

passed = 0
failed = 0
skipped = 0


def ok(test_id, desc):
    global passed
    passed += 1
    print(f"  [PASS] {test_id}: {desc}")


def fail(test_id, desc, detail=""):
    global failed
    failed += 1
    print(f"  [FAIL] {test_id}: {desc}")
    if detail:
        print(f"         {detail}")


def skip(test_id, desc, reason=""):
    global skipped
    skipped += 1
    print(f"  [SKIP] {test_id}: {desc} ({reason})")


def check_service(url, name):
    try:
        requests.get(url, timeout=2)
        return True
    except Exception:
        return False


def external_headers():
    """External invoke 请求需要 token 认证（复用 IM inbound token）"""
    h = {"Content-Type": "application/json"}
    if IM_INBOUND_TOKEN:
        h["Authorization"] = f"Bearer {IM_INBOUND_TOKEN}"
    return h


def reset_mock():
    """重置 mock 状态"""
    requests.delete(f"{MOCK_URL}/mock/im-messages")
    requests.delete(f"{MOCK_URL}/mock/sse-requests")
    requests.delete(f"{MOCK_URL}/mock/switches")


def build_auth_protocol(token, source, instance_id):
    """构建 WS 握手认证子协议"""
    auth_payload = json.dumps({"token": token, "source": source, "instanceId": instance_id})
    encoded = base64.urlsafe_b64encode(auth_payload.encode()).decode().rstrip("=")
    return f"auth.{encoded}"


def connect_external_ws(token=None, source="im", instance_id="e2e-1", timeout=5):
    """建立外部 WS 连接，返回 (ws, error)。连接后 recv 超时固定为 3 秒（保证 ping 能定期发送）"""
    if token is None:
        token = IM_INBOUND_TOKEN
    auth_protocol = build_auth_protocol(token, source, instance_id)
    ws = websocket.WebSocket()
    ws.settimeout(timeout)
    try:
        ws.connect(WS_URL, subprotocols=[auth_protocol])
        ws.settimeout(3)  # 连接成功后切短 recv 超时，让 ping 保活逻辑正常执行
        return ws, None
    except Exception as e:
        return None, str(e)


# ============================================================
# REST 校验测试（只需 SS）
# ============================================================

def test_envelope_validation():
    """EX-M01: External invoke 信封校验"""
    print("\n[EX-M01] External invoke 信封校验")

    # missing action
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "dm-001", "assistantAccount": "assist-01",
        "payload": {"content": "hello"}
    })
    if resp.status_code == 400:
        ok("EX-M01-01", "缺少 action 返回 400")
    else:
        fail("EX-M01-01", "缺少 action", f"status={resp.status_code}")

    # invalid action
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "invalid_action",
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "dm-001", "assistantAccount": "assist-01",
        "payload": {}
    })
    if resp.status_code == 400:
        ok("EX-M01-02", "无效 action 返回 400")
    else:
        fail("EX-M01-02", "无效 action", f"status={resp.status_code}")

    # missing sessionId
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat",
        "businessDomain": "im", "sessionType": "direct",
        "assistantAccount": "assist-01",
        "payload": {"content": "hello"}
    })
    if resp.status_code == 400:
        ok("EX-M01-03", "缺少 sessionId 返回 400")
    else:
        fail("EX-M01-03", "缺少 sessionId", f"status={resp.status_code}")

    # invalid sessionType
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat",
        "businessDomain": "im", "sessionType": "invalid",
        "sessionId": "dm-001", "assistantAccount": "assist-01",
        "payload": {"content": "hello"}
    })
    if resp.status_code == 400:
        ok("EX-M01-04", "无效 sessionType 返回 400")
    else:
        fail("EX-M01-04", "无效 sessionType", f"status={resp.status_code}")


def test_payload_validation():
    """EX-M02: External invoke payload 校验"""
    print("\n[EX-M02] External invoke payload 校验")

    # chat without content
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat",
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "dm-001", "assistantAccount": "assist-01",
        "payload": {"msgType": "text"}
    })
    if resp.status_code == 400:
        ok("EX-M02-01", "chat 缺少 content 返回 400")
    else:
        fail("EX-M02-01", "chat 缺少 content", f"status={resp.status_code}")

    # question_reply without toolCallId
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "question_reply",
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "dm-001", "assistantAccount": "assist-01",
        "payload": {"content": "answer"}
    })
    if resp.status_code == 400:
        ok("EX-M02-02", "question_reply 缺少 toolCallId 返回 400")
    else:
        fail("EX-M02-02", "question_reply 缺少 toolCallId", f"status={resp.status_code}")

    # permission_reply with invalid response
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "permission_reply",
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "dm-001", "assistantAccount": "assist-01",
        "payload": {"permissionId": "perm-1", "response": "invalid"}
    })
    if resp.status_code == 400:
        ok("EX-M02-03", "permission_reply 无效 response 返回 400")
    else:
        fail("EX-M02-03", "permission_reply 无效 response", f"status={resp.status_code}")

    # permission_reply missing permissionId
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "permission_reply",
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "dm-001", "assistantAccount": "assist-01",
        "payload": {"response": "once"}
    })
    if resp.status_code == 400:
        ok("EX-M02-04", "permission_reply 缺少 permissionId 返回 400")
    else:
        fail("EX-M02-04", "permission_reply 缺少 permissionId", f"status={resp.status_code}")

    # rebuild with empty payload — should pass validation
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "rebuild",
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "dm-001", "assistantAccount": "test-business-ak",
        "payload": {}
    })
    if resp.status_code == 200:
        ok("EX-M02-05", "rebuild 空 payload 通过校验")
    else:
        fail("EX-M02-05", "rebuild 空 payload", f"status={resp.status_code}")


def test_auth_required():
    """EX-M03: External invoke 需要认证"""
    print("\n[EX-M03] External invoke 认证校验")

    # 无 token
    resp = requests.post(f"{SS_URL}/api/external/invoke",
                         headers={"Content-Type": "application/json"},
                         json={
                             "action": "chat",
                             "businessDomain": "im", "sessionType": "direct",
                             "sessionId": "dm-001", "assistantAccount": "assist-01",
                             "payload": {"content": "hello"}
                         })
    if resp.status_code in (401, 403):
        ok("EX-M03-01", f"无 token 返回 {resp.status_code}")
    else:
        fail("EX-M03-01", "无 token 应返回 401/403", f"status={resp.status_code}")

    # 错误 token
    resp = requests.post(f"{SS_URL}/api/external/invoke",
                         headers={"Content-Type": "application/json",
                                  "Authorization": "Bearer wrong-token"},
                         json={
                             "action": "chat",
                             "businessDomain": "im", "sessionType": "direct",
                             "sessionId": "dm-001", "assistantAccount": "assist-01",
                             "payload": {"content": "hello"}
                         })
    if resp.status_code in (401, 403):
        ok("EX-M03-02", f"错误 token 返回 {resp.status_code}")
    else:
        fail("EX-M03-02", "错误 token 应返回 401/403", f"status={resp.status_code}")


# ============================================================
# 全链路测试（需要 SS + GW + Mock）
# ============================================================

REAL_AGENT_AK = os.environ.get("REAL_AGENT_AK", "test-ak-001")
AGENT_WAIT_SECONDS = int(os.environ.get("AGENT_WAIT_SECONDS", "35"))


def warmup_session(session_id, assistant_account=None):
    """预热 session：发一条消息让 session 创建完成，等待 AI 回复处理完毕"""
    if assistant_account is None:
        assistant_account = REAL_AGENT_AK
    requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat",
        "businessDomain": "im",
        "sessionType": "direct",
        "sessionId": session_id,
        "assistantAccount": assistant_account,
        "payload": {"content": "Reply with exactly: WARMUP_OK", "msgType": "text"}
    })
    time.sleep(AGENT_WAIT_SECONDS)


def check_agent_online():
    """检查真实 agent 是否在线"""
    try:
        resp = requests.get(f"{GW_URL}/api/gateway/agents",
                            headers={"Authorization": "Bearer changeme"}, timeout=3)
        agents = resp.json().get("data", [])
        for a in agents:
            if a.get("ak") == REAL_AGENT_AK and a.get("status") == "ONLINE":
                return True
    except Exception:
        pass
    return False


def collect_ws_messages(ws, timeout_sec):
    """从 WS 收集所有消息直到超时，定期发 ping 保持连接活跃"""
    messages = []
    start = time.time()
    last_ping = time.time()
    while time.time() - start < timeout_sec:
        if time.time() - last_ping > 10:
            try:
                ws.send(json.dumps({"action": "ping"}))
                last_ping = time.time()
            except Exception:
                pass
        try:
            msg = ws.recv()
            if msg:
                parsed = json.loads(msg)
                # 过滤掉 pong 响应
                if parsed.get("action") != "pong":
                    messages.append(parsed)
        except websocket.WebSocketTimeoutException:
            continue
        except Exception:
            break
    return messages


def test_ex01_ws_push_content_verify():
    """EX-01: WS 推送 — 真实 agent 回复内容精确验证"""
    print("\n[EX-01] WS 推送 — agent 回复内容精确验证")

    echo_token = f"ECHO_WS_{int(time.time())}"
    session_id = f"ex-e2e-ws-verify-{int(time.time())}"

    # 1. 预热 session
    reset_mock()
    warmup_session(session_id)
    ok("EX-01-00", "Session 预热完成")
    reset_mock()
    time.sleep(1)

    # 2. 建立 WS
    ws, err = connect_external_ws(timeout=AGENT_WAIT_SECONDS + 5)
    if ws is None:
        fail("EX-01-01", "WS 连接失败", err)
        return
    ok("EX-01-01", "WS 连接建立")

    # 3. 发送带唯一标记的消息
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat",
        "businessDomain": "im",
        "sessionType": "direct",
        "sessionId": session_id,
        "assistantAccount": REAL_AGENT_AK,
        "payload": {"content": f"Reply with exactly: {echo_token}", "msgType": "text"}
    })
    if resp.status_code == 200:
        ok("EX-01-02", "External invoke 返回 200")
    else:
        fail("EX-01-02", "External invoke", f"status={resp.status_code}")
        ws.close()
        return

    # 4. 收集 WS 消息
    messages = collect_ws_messages(ws, AGENT_WAIT_SECONDS)
    ws.close()

    if len(messages) == 0:
        fail("EX-01-03", "WS 未收到任何消息")
        return
    ok("EX-01-03", f"WS 收到 {len(messages)} 条消息")

    # 5. 提取 text.done（取最后一个有 content 的）
    text_dones = [m for m in messages
                  if m.get("message", {}).get("type") == "text.done"
                  and m.get("message", {}).get("content")]
    if not text_dones:
        fail("EX-01-04", "WS 未收到 text.done 消息")
        return

    final_content = text_dones[-1]["message"]["content"]
    if echo_token in final_content:
        ok("EX-01-04", f"text.done 内容精确匹配: {repr(final_content)}")
    else:
        fail("EX-01-04", f"text.done 内容不匹配",
             f"expected contains: {echo_token}, got: {repr(final_content)}")

    # 6. 验证 text.delta 拼接 = text.done（只取最后一轮的 delta，跳过 warmup 残留）
    # 找到最后一个 text.done 的 messageId，只匹配同一 messageId 的 delta
    last_done_msg_id = text_dones[-1]["message"].get("messageId")
    if last_done_msg_id:
        text_deltas = [m.get("message", {}).get("content", "")
                       for m in messages
                       if m.get("message", {}).get("type") == "text.delta"
                       and m.get("message", {}).get("messageId") == last_done_msg_id]
    else:
        # 没有 messageId，取最后一个 step.start 之后的 delta
        last_step_idx = max((i for i, m in enumerate(messages)
                             if m.get("message", {}).get("type") == "step.start"), default=-1)
        text_deltas = [m.get("message", {}).get("content", "")
                       for m in messages[last_step_idx + 1:]
                       if m.get("message", {}).get("type") == "text.delta"]
    if text_deltas:
        concatenated = "".join(text_deltas)
        if concatenated == final_content:
            ok("EX-01-05", f"text.delta 拼接 = text.done（{len(text_deltas)} 个 delta）")
        else:
            fail("EX-01-05", "text.delta 拼接与 text.done 不一致",
                 f"delta concat={repr(concatenated)}, done={repr(final_content)}")
    else:
        skip("EX-01-05", "无 text.delta 消息")

    # 7. 验证每条消息都有 domain=im
    all_have_domain = all(m.get("domain") == "im" for m in messages if "domain" in m)
    if all_have_domain:
        ok("EX-01-06", "所有推送消息 domain=im")
    else:
        fail("EX-01-06", "部分消息 domain 不为 im")

    # 8. 验证 IM REST 未被调用（WS 优先）
    time.sleep(1)
    im_msgs = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if len(im_msgs) == 0:
        ok("EX-01-07", "IM REST 未被调用（WS 优先）")
    else:
        fail("EX-01-07", "WS 连接时 IM REST 不应被调用", f"收到 {len(im_msgs)} 条")


def test_ex04_rest_fallback_content_verify():
    """EX-04: REST 降级 — 真实 agent 回复内容精确验证"""
    print("\n[EX-04] REST 降级 — agent 回复内容精确验证")

    echo_token = f"REST_VERIFY_{int(time.time())}"
    session_id = f"ex-e2e-rest-verify-{int(time.time())}"

    # 1. 预热 session
    reset_mock()
    warmup_session(session_id)
    ok("EX-04-00", "Session 预热完成")
    reset_mock()
    time.sleep(1)

    # 2. 不建 WS，直接发消息（应走 REST 出站）
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat",
        "businessDomain": "im",
        "sessionType": "direct",
        "sessionId": session_id,
        "assistantAccount": REAL_AGENT_AK,
        "payload": {"content": f"Reply with exactly: {echo_token}", "msgType": "text"}
    })
    if resp.status_code == 200:
        ok("EX-04-01", "External invoke 返回 200")
    else:
        fail("EX-04-01", "External invoke", f"status={resp.status_code}")
        return

    # 3. 等待 agent 回复
    time.sleep(AGENT_WAIT_SECONDS)

    # 4. 验证 IM REST 出站内容
    im_msgs = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if len(im_msgs) == 0:
        fail("EX-04-02", "IM REST 未收到消息")
        return
    ok("EX-04-02", f"IM REST 收到 {len(im_msgs)} 条消息")

    # 在所有 IM 消息中搜索包含 echo token 的消息
    matched = [m for m in im_msgs if echo_token in m.get("body", {}).get("content", "")]
    if matched:
        ok("EX-04-03", f"IM REST 内容精确匹配: {repr(matched[0].get('body', {}).get('content', ''))}")
    else:
        all_content = [m.get("body", {}).get("content", "")[:60] for m in im_msgs]
        fail("EX-04-03", f"IM REST 内容不匹配",
             f"expected contains: {echo_token}, messages: {all_content}")


def test_ex05_ws_disconnect_then_rest():
    """EX-05: WS 断开后降级 — 精确验证 REST 收到 agent 回复"""
    print("\n[EX-05] WS 断开后降级 — 精确验证")

    echo_token = f"FALLBACK_{int(time.time())}"
    session_id = f"ex-e2e-fallback-v2-{int(time.time())}"

    # 1. 预热
    reset_mock()
    warmup_session(session_id)
    ok("EX-05-00", "Session 预热完成")

    # 2. 建 WS → 断开
    ws, err = connect_external_ws()
    if ws is None:
        fail("EX-05-01", "WS 连接失败", err)
        return
    ok("EX-05-01", "WS 连接建立")
    ws.close()
    time.sleep(1)
    ok("EX-05-02", "WS 已断开")

    # 3. 清空 mock，发消息
    reset_mock()
    time.sleep(1)

    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat",
        "businessDomain": "im",
        "sessionType": "direct",
        "sessionId": session_id,
        "assistantAccount": REAL_AGENT_AK,
        "payload": {"content": f"Reply with exactly: {echo_token}", "msgType": "text"}
    })
    if resp.status_code == 200:
        ok("EX-05-03", "External invoke 返回 200")
    else:
        fail("EX-05-03", "External invoke", f"status={resp.status_code}")
        return

    # 4. 等待并验证
    time.sleep(AGENT_WAIT_SECONDS)

    im_msgs = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if len(im_msgs) == 0:
        fail("EX-05-04", "IM REST 未收到消息（WS 已断开，应降级到 REST）")
        return
    ok("EX-05-04", f"IM REST 收到 {len(im_msgs)} 条消息")

    matched = [m for m in im_msgs if echo_token in m.get("body", {}).get("content", "")]
    if matched:
        ok("EX-05-05", f"降级 REST 内容精确匹配: {repr(matched[0].get('body', {}).get('content', ''))}")
    else:
        all_content = [m.get("body", {}).get("content", "")[:60] for m in im_msgs]
        fail("EX-05-05", f"降级 REST 内容不匹配",
             f"expected contains: {echo_token}, messages: {all_content}")


def test_ex02_ws_connect_auth():
    """EX-02: External WS 连接 + 认证"""
    print("\n[EX-02] External WS 连接认证")

    ws, err = connect_external_ws()
    if ws is not None:
        ok("EX-02-01", "WS 连接成功（token 认证通过）")

        ws.send(json.dumps({"action": "ping"}))
        try:
            pong = json.loads(ws.recv())
            if pong.get("action") == "pong":
                ok("EX-02-02", "心跳 ping/pong 正常")
            else:
                fail("EX-02-02", "心跳响应不正确", f"got: {pong}")
        except Exception as e:
            fail("EX-02-02", "心跳接收失败", str(e))

        ws.close()
        ok("EX-02-03", "WS 连接关闭成功")
    else:
        fail("EX-02-01", "WS 连接失败", err)
        skip("EX-02-02", "心跳测试")
        skip("EX-02-03", "关闭测试")


def test_ex03_ws_invalid_auth():
    """EX-03: External WS 错误认证"""
    print("\n[EX-03] External WS 错误认证")

    ws, err = connect_external_ws(token="wrong-token")
    if ws is None:
        ok("EX-03-01", "错误 token 拒绝连接")
    else:
        fail("EX-03-01", "错误 token 不应建立连接")
        ws.close()

    # 缺少 source
    auth_payload = json.dumps({"token": IM_INBOUND_TOKEN})
    encoded = base64.urlsafe_b64encode(auth_payload.encode()).decode().rstrip("=")
    ws2 = websocket.WebSocket()
    ws2.settimeout(5)
    try:
        ws2.connect(WS_URL, subprotocols=[f"auth.{encoded}"])
        fail("EX-03-02", "缺少 source 不应建立连接")
        ws2.close()
    except Exception:
        ok("EX-03-02", "缺少 source/instanceId 拒绝连接")


    # Removed — replaced by test_ex01_ws_push_content_verify, test_ex04_rest_fallback_content_verify, test_ex05_ws_disconnect_then_rest


def test_ex06_permission_flow():
    """EX-06: Permission 全链路 — ask → reply → agent 继续执行 → 验证输出"""
    print("\n[EX-06] Permission 全链路验证")

    perm_token = f"PERM_E2E_{int(time.time())}"
    session_id = f"ex-e2e-perm-flow-{int(time.time())}"

    # 1. 建 WS + 预热新 session
    ws, err = connect_external_ws(timeout=AGENT_WAIT_SECONDS + 5)
    if ws is None:
        fail("EX-06-01", "WS 连接失败", err)
        return
    ok("EX-06-01", "WS 连接建立")

    # 预热（消耗 warmup 消息，保持心跳）
    requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat", "businessDomain": "im", "sessionType": "direct",
        "sessionId": session_id, "assistantAccount": REAL_AGENT_AK,
        "payload": {"content": "Reply OK", "msgType": "text"}
    })
    start = time.time()
    last_ping = time.time()
    while time.time() - start < AGENT_WAIT_SECONDS:
        if time.time() - last_ping > 10:
            try:
                ws.send(json.dumps({"action": "ping"}))
                last_ping = time.time()
            except Exception:
                pass
        try:
            ws.recv()
        except Exception:
            continue
    ok("EX-06-02", "Session 预热完成")

    # 2. 发触发 permission 的消息
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat", "businessDomain": "im", "sessionType": "direct",
        "sessionId": session_id, "assistantAccount": REAL_AGENT_AK,
        "payload": {"content": f"Run this bash command: echo {perm_token}", "msgType": "text"}
    })
    if resp.status_code != 200:
        fail("EX-06-03", "Invoke 失败", f"status={resp.status_code}")
        ws.close()
        return
    ok("EX-06-03", "发送 permission 触发消息")

    # 3. 等待 permission.ask
    perm_id = None
    start = time.time()
    while time.time() - start < 30:
        try:
            raw = ws.recv()
            if raw:
                m = json.loads(raw)
                if m.get("message", {}).get("type") == "permission.ask":
                    perm_id = m["message"].get("permissionId")
                    perm_type = m["message"].get("permType", "")
                    title = m["message"].get("title", "")
                    break
        except websocket.WebSocketTimeoutException:
            continue
        except Exception:
            break

    if not perm_id:
        fail("EX-06-04", "未收到 permission.ask")
        ws.close()
        return
    ok("EX-06-04", f"收到 permission.ask: permissionId={perm_id}, title={title}")

    # 4. 通过 external invoke 回复 permission_reply
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "permission_reply", "businessDomain": "im", "sessionType": "direct",
        "sessionId": session_id, "assistantAccount": REAL_AGENT_AK,
        "payload": {"permissionId": perm_id, "response": "once"}
    })
    if resp.status_code == 200:
        ok("EX-06-05", "permission_reply 发送成功")
    else:
        fail("EX-06-05", "permission_reply 失败", f"status={resp.status_code}")
        ws.close()
        return

    # 5. 保持心跳，等待 agent 执行工具 + 返回文本（permission 后 agent 需要额外时���）
    final_content = None
    last_ping = time.time()
    start = time.time()
    post_perm_timeout = AGENT_WAIT_SECONDS + 20  # permission 后需要更长等待
    while time.time() - start < post_perm_timeout:
        # 每 15 秒发一次 ping 保持连接活跃
        if time.time() - last_ping > 10:
            try:
                ws.send(json.dumps({"action": "ping"}))
                last_ping = time.time()
            except Exception:
                pass
        try:
            raw = ws.recv()
            if raw:
                m = json.loads(raw)
                t = m.get("message", {}).get("type", "")
                c = m.get("message", {}).get("content", "")
                if t == "text.done" and c:
                    final_content = c
        except websocket.WebSocketTimeoutException:
            continue
        except Exception:
            break

    ws.close()

    if final_content and perm_token in final_content:
        ok("EX-06-06", f"Agent 执行后输出精确匹配: {repr(final_content)}")
    elif final_content:
        fail("EX-06-06", f"Agent 输出不含预期内容",
             f"expected: {perm_token}, got: {repr(final_content)}")
    else:
        fail("EX-06-06", "permission_reply 后未收到 text.done")


def test_ex07_question_flow():
    """EX-07: Question 全链路 — question → reply → agent 继续 → 验证输出"""
    print("\n[EX-07] Question 全链路验证")

    session_id = f"ex-e2e-question-{int(time.time())}"

    # 1. 建 WS + 预热新 session
    ws, err = connect_external_ws(timeout=AGENT_WAIT_SECONDS + 5)
    if ws is None:
        fail("EX-07-01", "WS 连接失败", err)
        return
    ok("EX-07-01", "WS 连接建立")

    requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat", "businessDomain": "im", "sessionType": "direct",
        "sessionId": session_id, "assistantAccount": REAL_AGENT_AK,
        "payload": {"content": "Reply OK", "msgType": "text"}
    })
    start = time.time()
    last_ping = time.time()
    while time.time() - start < AGENT_WAIT_SECONDS:
        if time.time() - last_ping > 10:
            try:
                ws.send(json.dumps({"action": "ping"}))
                last_ping = time.time()
            except Exception:
                pass
        try:
            ws.recv()
        except Exception:
            continue
    ok("EX-07-02", "Session 预热完成")

    # 2. 发触发 question 的消息
    question_prompt = (
        'Next, before doing anything, you MUST use the Question tool to ask me exactly 1 '
        'multiple-choice question:\n"Which approach: A or B?"\nOptions:\n'
        '- A (Recommended): minimal change only\n- B: full refactor\n'
        'Do NOT modify any files or run any commands until I answer.'
    )
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat", "businessDomain": "im", "sessionType": "direct",
        "sessionId": session_id, "assistantAccount": REAL_AGENT_AK,
        "payload": {"content": question_prompt, "msgType": "text"}
    })
    if resp.status_code != 200:
        fail("EX-07-03", "Invoke 失败", f"status={resp.status_code}")
        ws.close()
        return
    ok("EX-07-03", "发送 question 触发消息")

    # 3. 等待 question 事件
    tool_call_id = None
    question_text = None
    options = None
    start = time.time()
    last_ping = time.time()
    while time.time() - start < AGENT_WAIT_SECONDS:
        if time.time() - last_ping > 10:
            try:
                ws.send(json.dumps({"action": "ping"}))
                last_ping = time.time()
            except Exception:
                pass
        try:
            raw = ws.recv()
            if raw:
                m = json.loads(raw)
                if m.get("action") == "pong":
                    continue
                if m.get("message", {}).get("type") == "question":
                    msg = m["message"]
                    tool_call_id = msg.get("toolCallId")
                    question_text = msg.get("question", "")
                    options = msg.get("options", [])
                    break
        except websocket.WebSocketTimeoutException:
            continue
        except Exception:
            break

    if not tool_call_id:
        fail("EX-07-04", "未收到 question 事件")
        ws.close()
        return
    ok("EX-07-04", f"收到 question: toolCallId={tool_call_id}, question={question_text}, options={options}")

    # 4. 验证 question 内容
    if "A or B" in question_text or "approach" in question_text.lower():
        ok("EX-07-05", f"Question 内容包含预期问题")
    else:
        fail("EX-07-05", "Question 内容不匹配", f"got: {question_text}")

    if len(options) >= 2:
        ok("EX-07-06", f"Question 有 {len(options)} 个选项: {options}")
    else:
        fail("EX-07-06", "Question 选项不足", f"got: {options}")

    # 5. 通过 external invoke 回复 question_reply
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "question_reply", "businessDomain": "im", "sessionType": "direct",
        "sessionId": session_id, "assistantAccount": REAL_AGENT_AK,
        "payload": {"content": "A", "toolCallId": tool_call_id}
    })
    if resp.status_code == 200:
        ok("EX-07-07", "question_reply 发送成功")
    else:
        fail("EX-07-07", "question_reply 失败", f"status={resp.status_code}")
        ws.close()
        return

    # 6. 等待 agent 继续，验证回复包含选择确认
    final_content = None
    start = time.time()
    last_ping = time.time()
    post_question_timeout = AGENT_WAIT_SECONDS + 20
    while time.time() - start < post_question_timeout:
        if time.time() - last_ping > 10:
            try:
                ws.send(json.dumps({"action": "ping"}))
                last_ping = time.time()
            except Exception:
                pass
        try:
            raw = ws.recv()
            if raw:
                m = json.loads(raw)
                if m.get("action") == "pong":
                    continue
                t = m.get("message", {}).get("type", "")
                c = m.get("message", {}).get("content", "")
                if t == "text.done" and c:
                    final_content = c
        except websocket.WebSocketTimeoutException:
            continue
        except Exception:
            break

    ws.close()

    if final_content:
        ok("EX-07-08", f"Agent 回复: {repr(final_content[:80])}")
    else:
        fail("EX-07-08", "question_reply 后未收到 text.done")


def test_ex08_im_inbound_regression():
    """EX-08: IM Inbound 回归 — 重构后原接口仍正常"""
    print("\n[EX-08] IM Inbound 回归验证")

    # 业务助手
    reset_mock()
    time.sleep(1)
    sid = f"im-regress-biz-{int(time.time())}"
    resp = requests.post(f"{SS_URL}/api/inbound/messages", headers=external_headers(), json={
        "businessDomain": "im", "sessionType": "direct", "sessionId": sid,
        "assistantAccount": "test-business-ak", "content": "IM inbound regression", "msgType": "text"
    })
    if resp.status_code == 200:
        ok("EX-08-01", "IM Inbound 业务助手返回 200")
    else:
        fail("EX-08-01", "IM Inbound 业务助手", f"status={resp.status_code}")

    time.sleep(8)
    sse_reqs = requests.get(f"{MOCK_URL}/mock/sse-requests").json()
    if len(sse_reqs) > 0:
        ok("EX-08-02", f"业务助手走云端 SSE（{len(sse_reqs)} 请求）")
    else:
        fail("EX-08-02", "业务助手未走云端 SSE")

    im_msgs = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if len(im_msgs) > 0:
        ok("EX-08-03", f"IM REST 出站收到 {len(im_msgs)} 条回复")
    else:
        fail("EX-08-03", "IM REST 出站未收到回复")

    # 个人助手（真实 agent）
    reset_mock()
    time.sleep(1)
    echo = f"IM_REGRESS_PERSONAL_{int(time.time())}"
    sid2 = f"im-regress-personal-{int(time.time())}"
    resp2 = requests.post(f"{SS_URL}/api/inbound/messages", headers=external_headers(), json={
        "businessDomain": "im", "sessionType": "direct", "sessionId": sid2,
        "assistantAccount": REAL_AGENT_AK,
        "content": f"Reply with exactly: {echo}", "msgType": "text"
    })
    if resp2.status_code == 200:
        ok("EX-08-04", "IM Inbound 个人助手返回 200")
    else:
        fail("EX-08-04", "IM Inbound 个人助手", f"status={resp2.status_code}")

    time.sleep(AGENT_WAIT_SECONDS)
    sse_reqs2 = requests.get(f"{MOCK_URL}/mock/sse-requests").json()
    if len(sse_reqs2) == 0:
        ok("EX-08-05", "个人助手未走云端（正确）")
    else:
        fail("EX-08-05", "个人助手不应走云端", f"收到 {len(sse_reqs2)} SSE 请求")

    im_msgs2 = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if im_msgs2 and echo in im_msgs2[-1].get("body", {}).get("content", ""):
        ok("EX-08-06", f"个人助手 IM 回复精确匹配: {echo}")
    elif im_msgs2:
        fail("EX-08-06", "个人助手 IM 回复不匹配",
             f"got: {repr(im_msgs2[-1].get('body', {}).get('content', '')[:80])}")
    else:
        fail("EX-08-06", "个人助手 IM 未收到回复")


def test_ex09_business_ws_push():
    """EX-09: 业务助手云端 + WS 推送"""
    print("\n[EX-09] 业务助手云端 + WS 推送")

    reset_mock()
    time.sleep(1)
    sid = f"ext-biz-ws-{int(time.time())}"

    # 预热
    warmup_session(sid, assistant_account="test-business-ak")
    ok("EX-09-00", "业务助手 session 预热完成")
    reset_mock()
    time.sleep(1)

    # 建 WS
    ws, err = connect_external_ws(timeout=15)
    if ws is None:
        fail("EX-09-01", "WS 连接失败", err)
        return
    ok("EX-09-01", "WS 连接建立")

    # 发消息
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat", "businessDomain": "im", "sessionType": "direct",
        "sessionId": sid, "assistantAccount": "test-business-ak",
        "payload": {"content": "BIZ_CLOUD_WS_TEST", "msgType": "text"}
    })
    if resp.status_code == 200:
        ok("EX-09-02", "External invoke 返回 200")
    else:
        fail("EX-09-02", "External invoke", f"status={resp.status_code}")
        ws.close()
        return

    messages = collect_ws_messages(ws, 10)
    ws.close()

    # 验证走了云端
    sse_reqs = requests.get(f"{MOCK_URL}/mock/sse-requests").json()
    if len(sse_reqs) > 0:
        ok("EX-09-03", f"走云端 SSE（{len(sse_reqs)} 请求）")
    else:
        fail("EX-09-03", "未走云端 SSE")

    # 验证 WS 收到 StreamMessage
    if len(messages) > 0:
        ok("EX-09-04", f"WS 收到 {len(messages)} 条 StreamMessage")
        types = set(m.get("message", {}).get("type", "") for m in messages)
        ok("EX-09-05", f"消息类型: {types}")
    else:
        fail("EX-09-04", "WS 未收到消息")

    # 验证 IM REST 未调用
    im_msgs = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if len(im_msgs) == 0:
        ok("EX-09-06", "IM REST 未调用（WS 优先）")
    else:
        fail("EX-09-06", "WS 连接时 IM REST 不应调用", f"收到 {len(im_msgs)} 条")


def test_ex10_session_lifecycle():
    """EX-10: Session 新建 + Rebuild"""
    print("\n[EX-10] Session 新建 + Rebuild")

    # 新建
    reset_mock()
    time.sleep(1)
    sid = f"lifecycle-{int(time.time())}"
    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat", "businessDomain": "im", "sessionType": "direct",
        "sessionId": sid, "assistantAccount": "test-business-ak",
        "payload": {"content": "init session", "msgType": "text"}
    })
    if resp.status_code == 200:
        ok("EX-10-01", "新 session chat 返回 200")
    else:
        fail("EX-10-01", "新 session chat", f"status={resp.status_code}")
        return

    time.sleep(8)
    sse = requests.get(f"{MOCK_URL}/mock/sse-requests").json()
    if len(sse) > 0:
        ok("EX-10-02", "新 session 创建成功 + 云端收到请求")
    else:
        fail("EX-10-02", "新 session 未走云端")

    # 查 session toolSessionId
    sessions = requests.get(f"{SS_URL}/api/skill/sessions",
                            headers={"Cookie": "userId=900001"}).json()
    found = [s for s in sessions.get("data", {}).get("content", [])
             if s.get("businessSessionId") == sid]
    old_tool = found[0].get("toolSessionId") if found else None
    if old_tool:
        ok("EX-10-03", f"toolSessionId 已绑定: {old_tool}")
    else:
        fail("EX-10-03", "toolSessionId 未绑定")
        return

    # Rebuild
    resp2 = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "rebuild", "businessDomain": "im", "sessionType": "direct",
        "sessionId": sid, "assistantAccount": "test-business-ak", "payload": {}
    })
    if resp2.status_code == 200:
        ok("EX-10-04", "Rebuild 返回 200")
    else:
        fail("EX-10-04", "Rebuild", f"status={resp2.status_code}")

    time.sleep(3)

    # Rebuild 后 session 仍可发消息
    reset_mock()
    time.sleep(1)
    resp3 = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "chat", "businessDomain": "im", "sessionType": "direct",
        "sessionId": sid, "assistantAccount": "test-business-ak",
        "payload": {"content": "post-rebuild msg", "msgType": "text"}
    })
    time.sleep(8)
    sse3 = requests.get(f"{MOCK_URL}/mock/sse-requests").json()
    if len(sse3) > 0:
        ok("EX-10-05", "Rebuild 后 session 可用（云端收到请求）")
    else:
        fail("EX-10-05", "Rebuild 后 session 不可用")


def test_ex11_rebuild():
    """EX-11: Rebuild action（无已有 session）"""
    print("\n[EX-06] Rebuild action")

    resp = requests.post(f"{SS_URL}/api/external/invoke", headers=external_headers(), json={
        "action": "rebuild",
        "businessDomain": "im",
        "sessionType": "direct",
        "sessionId": f"ex-e2e-rebuild-{int(time.time())}",
        "assistantAccount": REAL_AGENT_AK,
        "payload": {}
    })

    if resp.status_code == 200:
        resp_data = resp.json()
        if resp_data.get("code", 0) == 0:
            ok("EX-11-01", "Rebuild 返回成功")
        else:
            ok("EX-07-01", f"Rebuild 返回 200 (code={resp_data.get('code')})")
    else:
        fail("EX-11-01", "Rebuild", f"status={resp.status_code}")


def test_ex07_multi_source():
    """EX-07: 多 source WS 连接"""
    print("\n[EX-07] 多 source WS 连接")

    ws1, err1 = connect_external_ws(source="im", instance_id="im-e2e-1")
    ws2, err2 = connect_external_ws(source="crm", instance_id="crm-e2e-1")

    if ws1 is not None:
        ok("EX-07-01", "source=im WS 连接成功")
    else:
        fail("EX-07-01", "source=im WS 连接失败", err1)

    if ws2 is not None:
        ok("EX-07-02", "source=crm WS 连接成功")
    else:
        fail("EX-07-02", "source=crm WS 连接失败", err2)

    for ws, name in [(ws1, "im"), (ws2, "crm")]:
        if ws is not None:
            ws.send(json.dumps({"action": "ping"}))
            try:
                pong = json.loads(ws.recv())
                if pong.get("action") == "pong":
                    ok(f"EX-07-03-{name}", f"source={name} 心跳正常")
                else:
                    fail(f"EX-07-03-{name}", f"source={name} 心跳异常")
            except Exception as e:
                fail(f"EX-07-03-{name}", f"source={name} 心跳失败", str(e))

    if ws1:
        ws1.close()
    if ws2:
        ws2.close()


# ============================================================
# 主入口
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="External WebSocket Channel E2E Test")
    parser.add_argument("--mock-only", action="store_true",
                        help="只测试 REST 校验（需要 SS，不需要 GW/Mock）")
    args = parser.parse_args()

    print("=" * 60)
    print("External WebSocket Channel E2E Test")
    print("=" * 60)

    if not IM_INBOUND_TOKEN:
        print("\nWARN: IM_INBOUND_TOKEN not set.")
        print("  Set via: export IM_INBOUND_TOKEN=your_token")

    ss_ok = check_service(f"{SS_URL}/api/admin/configs?type=test", "SS")
    if ss_ok:
        print(f"\nSkill Server: OK ({SS_URL})")
    else:
        print(f"\nSkill Server: NOT RUNNING ({SS_URL})")
        print("  SS is required for all tests. Start it first.")
        sys.exit(1)

    # REST 校验测试（只需 SS）
    test_envelope_validation()
    test_payload_validation()
    test_auth_required()

    if args.mock_only:
        print("\n--mock-only mode, skip WS and full E2E tests")
    else:
        mock_ok = check_service(f"{MOCK_URL}/mock/health", "Mock")
        gw_ok = check_service(f"{GW_URL}/actuator/health", "GW")

        if mock_ok:
            print(f"Mock Server: OK ({MOCK_URL})")
        else:
            print(f"Mock Server: NOT RUNNING ({MOCK_URL})")

        if gw_ok:
            print(f"AI Gateway: OK ({GW_URL})")
        else:
            print(f"AI Gateway: NOT RUNNING ({GW_URL})")

        # WS 连接测试（只需 SS）
        test_ex02_ws_connect_auth()
        test_ex03_ws_invalid_auth()
        test_ex07_multi_source()

        # 真实 agent 全链路测试（需要 SS + GW + Mock + Agent 在线）
        if gw_ok and mock_ok:
            agent_online = check_agent_online()
            if agent_online:
                print(f"Real Agent: ONLINE (ak={REAL_AGENT_AK})")
                test_ex01_ws_push_content_verify()
                test_ex04_rest_fallback_content_verify()
                test_ex05_ws_disconnect_then_rest()
                test_ex06_permission_flow()
                test_ex07_question_flow()
            else:
                print(f"Real Agent: OFFLINE (ak={REAL_AGENT_AK})")
                skip("EX-01~07", "真实 agent 全链路测试", "Agent 不在线")

            # 以下测试不依赖 agent 在线（业务助手走 mock cloud）
            if agent_online:
                test_ex08_im_inbound_regression()
            else:
                # 业务助手部分仍可测
                skip("EX-08", "IM Inbound 回归（个人助手部分）", "Agent 不在线")

            test_ex09_business_ws_push()
            test_ex10_session_lifecycle()
            test_ex11_rebuild()
        else:
            skip("EX-01~06", "全链路测试", "需要 SS + GW + Mock 全部运行")

    # 结果汇总
    print("\n" + "=" * 60)
    total = passed + failed + skipped
    print(f"Result: PASS={passed} FAIL={failed} SKIP={skipped} TOTAL={total}")
    print("=" * 60)

    if failed > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
