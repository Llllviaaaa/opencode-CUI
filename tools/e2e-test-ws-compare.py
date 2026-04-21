"""
业务助手 vs 个人助手 WebSocket StreamMessage 实际对比验证

流程：
1. Mock OpenCode Agent 连接 GW（模拟个人助手的本地 agent）
2. 创建两个 MiniApp 会话：一个绑 personal AK，一个绑 business AK
3. 连接 WS 采集 StreamMessage
4. 分别对两个会话发消息
5. 采集两组 WS StreamMessage
6. 逐字段对比：共有事件类型的字段结构是否一致
7. 验证云端特有事件是否符合协议规范

前提：SS + GW + Mock 运行中
"""

import sys, os, json, time, uuid, hmac, hashlib, base64, threading
import requests
import websocket

MOCK_URL = os.environ.get("MOCK_URL", "http://localhost:9999")
SS_URL = os.environ.get("SS_URL", "http://localhost:8082")
GW_URL = os.environ.get("GW_URL", "http://localhost:8081")
GW_WS_URL = "ws://localhost:8081/ws/agent"
SS_WS_URL = "ws://localhost:8082/ws/skill/stream"

# 测试用 AK/SK（个人助手 - 本地 agent）
PERSONAL_AK = "test-ak-e2e"
PERSONAL_SK = "test-sk-e2e-secret"
# test-ak-002 的 DB user_id=900001，mock resolve 的 ownerWelinkId 也是 900001
# 但 GW 的 isAkOwnedByUser 可能额外校验失败
# 尝试用与 AK 关联的实际 userId
USER_ID = "900001"

# 业务助手 AK
BUSINESS_AK = "test-business-ak"

passed = 0
failed = 0


def ok(tid, desc):
    global passed
    passed += 1
    print(f"  [PASS] {tid}: {desc}")


def fail(tid, desc, detail=""):
    global failed
    failed += 1
    print(f"  [FAIL] {tid}: {desc}")
    if detail:
        print(f"         {detail}")


# ============================================================
# Mock OpenCode Agent（模拟本地 agent）
# ============================================================

class MockOpenCodeAgent:
    """模拟 OpenCode 本地 agent，连接 GW WebSocket 并响应 invoke"""

    def __init__(self, ak, sk):
        self.ak = ak
        self.sk = sk
        self.ws = None
        self.connected = threading.Event()
        self.registered = threading.Event()
        self.invoke_received = threading.Event()
        self.last_invoke = None

    def _sign(self):
        ts = str(int(time.time()))
        nonce = uuid.uuid4().hex[:16]
        sign_str = f"{self.ak}{ts}{nonce}"
        raw = hmac.new(self.sk.encode(), sign_str.encode(), hashlib.sha256).digest()
        sign = base64.b64encode(raw).decode()
        return {"ak": self.ak, "ts": ts, "nonce": nonce, "sign": sign}

    def connect(self):
        auth = self._sign()
        auth_b64 = base64.b64encode(json.dumps(auth).encode()).decode()
        protocol = f"auth.{auth_b64}"

        def on_open(ws):
            self.connected.set()
            # 发送 register
            ws.send(json.dumps({
                "type": "register",
                "toolType": "channel",
                "toolVersion": "mock-1.0"
            }))

        def on_message(ws, message):
            data = json.loads(message)
            msg_type = data.get("type", "")

            if msg_type == "register_ok":
                self.registered.set()

            elif msg_type == "invoke":
                self.last_invoke = data
                self.invoke_received.set()
                # 自动响应：返回 OpenCode 格式事件
                self._respond_to_invoke(ws, data)

        def on_error(ws, error):
            pass

        self.ws = websocket.WebSocketApp(
            GW_WS_URL,
            subprotocols=[protocol],
            on_open=on_open,
            on_message=on_message,
            on_error=on_error,
            on_close=lambda ws, c, m: None
        )
        t = threading.Thread(target=self.ws.run_forever, daemon=True)
        t.start()
        return self.connected.wait(timeout=5) and self.registered.wait(timeout=10)

    def _respond_to_invoke(self, ws, invoke):
        """模拟 OpenCode 返回完整事件序列"""
        action = invoke.get("action", "")
        tool_session_id = invoke.get("payload", {}).get("toolSessionId") if isinstance(invoke.get("payload"), dict) else None

        if action == "create_session":
            # 返回 session_created
            welink_sid = invoke.get("welinkSessionId", "")
            ws.send(json.dumps({
                "type": "session_created",
                "toolSessionId": f"oc-session-{uuid.uuid4().hex[:8]}",
                "welinkSessionId": welink_sid
            }))
            return

        if action != "chat":
            return

        msg_id = f"oc-msg-{uuid.uuid4().hex[:8]}"
        part_text = f"oc-prt-text-{uuid.uuid4().hex[:6]}"
        part_think = f"oc-prt-think-{uuid.uuid4().hex[:6]}"

        events = [
            # thinking
            {"type": "tool_event", "toolSessionId": tool_session_id, "event": {
                "type": "message.part.updated", "properties": {
                    "sessionID": "s1", "messageID": msg_id,
                    "part": {"id": part_think, "sessionID": "s1", "messageID": msg_id,
                             "type": "reasoning", "text": ""}
                }
            }},
            {"type": "tool_event", "toolSessionId": tool_session_id, "event": {
                "type": "message.part.delta", "properties": {
                    "sessionID": "s1", "messageID": msg_id,
                    "partID": part_think, "delta": "Let me think..."
                }
            }},
            {"type": "tool_event", "toolSessionId": tool_session_id, "event": {
                "type": "message.part.updated", "properties": {
                    "sessionID": "s1", "messageID": msg_id,
                    "part": {"id": part_think, "sessionID": "s1", "messageID": msg_id,
                             "type": "reasoning", "text": "Let me think about this carefully."}
                }
            }},
            # text
            {"type": "tool_event", "toolSessionId": tool_session_id, "event": {
                "type": "message.part.updated", "properties": {
                    "sessionID": "s1", "messageID": msg_id,
                    "part": {"id": part_text, "sessionID": "s1", "messageID": msg_id,
                             "type": "text", "text": ""}
                }
            }},
            {"type": "tool_event", "toolSessionId": tool_session_id, "event": {
                "type": "message.part.delta", "properties": {
                    "sessionID": "s1", "messageID": msg_id,
                    "partID": part_text, "delta": "Here is "
                }
            }},
            {"type": "tool_event", "toolSessionId": tool_session_id, "event": {
                "type": "message.part.delta", "properties": {
                    "sessionID": "s1", "messageID": msg_id,
                    "partID": part_text, "delta": "the answer."
                }
            }},
            {"type": "tool_event", "toolSessionId": tool_session_id, "event": {
                "type": "message.part.updated", "properties": {
                    "sessionID": "s1", "messageID": msg_id,
                    "part": {"id": part_text, "sessionID": "s1", "messageID": msg_id,
                             "type": "text", "text": "Here is the answer."}
                }
            }},
            # message finish
            {"type": "tool_event", "toolSessionId": tool_session_id, "event": {
                "type": "message.updated", "properties": {
                    "sessionID": "s1", "messageID": msg_id,
                    "info": {"id": msg_id, "sessionID": "s1", "role": "assistant",
                             "finish": {"reason": "end_turn"}}
                }
            }},
            # session idle
            {"type": "tool_event", "toolSessionId": tool_session_id, "event": {
                "type": "session.idle", "properties": {"sessionID": "s1"}
            }},
            # tool_done
            {"type": "tool_done", "toolSessionId": tool_session_id}
        ]

        for evt in events:
            ws.send(json.dumps(evt))
            time.sleep(0.05)

    def close(self):
        if self.ws:
            self.ws.close()


# ============================================================
# WS StreamMessage 采集器
# ============================================================

def collect_ws_messages(duration_sec=12):
    """连接 SS WebSocket 采集 StreamMessage"""
    messages = []
    connected = threading.Event()

    ws = websocket.WebSocketApp(
        SS_WS_URL,
        cookie=f"userId={USER_ID}",
        on_message=lambda ws, msg: messages.append(json.loads(msg)),
        on_open=lambda ws: connected.set(),
        on_error=lambda ws, e: None,
        on_close=lambda ws, c, m: None
    )
    t = threading.Thread(target=ws.run_forever, daemon=True)
    t.start()
    connected.wait(timeout=5)
    time.sleep(duration_sec)
    ws.close()
    return messages


# ============================================================
# 字段对比
# ============================================================

# 个人助手标准字段
PART_FIELDS = {"type", "seq", "welinkSessionId", "emittedAt",
               "messageId", "sourceMessageId", "messageSeq", "role",
               "partId", "partSeq"}
MESSAGE_FIELDS = {"type", "seq", "welinkSessionId", "emittedAt",
                  "messageId", "role"}
SESSION_FIELDS = {"type", "seq", "welinkSessionId"}

PART_TYPES = {"text.delta", "text.done", "thinking.delta", "thinking.done",
              "tool.update", "question", "file", "permission.ask", "permission.reply",
              "planning.delta", "planning.done", "searching", "search_result",
              "reference", "ask_more"}
MESSAGE_TYPES = {"step.start", "step.done"}
SESSION_TYPES = {"session.status", "session.title", "session.error"}
SKIP_TYPES = {"snapshot", "streaming", "message.user", "agent.online", "agent.offline", "error"}
CLOUD_ONLY_TYPES = {"planning.delta", "planning.done", "searching", "search_result", "reference", "ask_more"}


def get_present_fields(msg):
    """获取消息中实际存在（非 null）的字段"""
    return {k for k, v in msg.items() if v is not None}


def compare_messages(personal_msgs, business_msgs):
    """对比两组 StreamMessage 的字段结构"""
    S = "COMPARE"
    print(f"\n[{S}] 字段对比")

    # 按 type 分组
    personal_by_type = {}
    for m in personal_msgs:
        t = m.get("type", "")
        if t not in SKIP_TYPES:
            personal_by_type.setdefault(t, []).append(m)

    business_by_type = {}
    for m in business_msgs:
        t = m.get("type", "")
        if t not in SKIP_TYPES:
            business_by_type.setdefault(t, []).append(m)

    print(f"  个人助手事件类型: {sorted(personal_by_type.keys())}")
    print(f"  业务助手事件类型: {sorted(business_by_type.keys())}")

    # 对比共有类型
    common_types = set(personal_by_type.keys()) & set(business_by_type.keys())
    print(f"  共有事件类型: {sorted(common_types)}")

    for etype in sorted(common_types):
        p_msg = personal_by_type[etype][0]
        b_msg = business_by_type[etype][0]

        p_fields = get_present_fields(p_msg)
        b_fields = get_present_fields(b_msg)

        # 检查必须字段
        if etype in PART_TYPES:
            required = PART_FIELDS
        elif etype in MESSAGE_TYPES:
            required = MESSAGE_FIELDS
        elif etype in SESSION_TYPES:
            required = SESSION_FIELDS
        else:
            required = set()

        p_missing = required - p_fields
        b_missing = required - b_fields

        if not p_missing and not b_missing:
            ok(f"CMP-{etype}", f"两者字段一致（必须字段 {len(required)} 个全有）")
        else:
            detail_parts = []
            if p_missing:
                detail_parts.append(f"个人缺: {p_missing}")
            if b_missing:
                detail_parts.append(f"业务缺: {b_missing}")
            fail(f"CMP-{etype}", "字段不一致", "; ".join(detail_parts))

    # 验证云端特有事件字段
    cloud_types_found = set(business_by_type.keys()) & CLOUD_ONLY_TYPES
    for etype in sorted(cloud_types_found):
        b_msg = business_by_type[etype][0]
        b_fields = get_present_fields(b_msg)
        missing = PART_FIELDS - b_fields
        if not missing:
            ok(f"CLOUD-{etype}", f"云端特有事件字段完整（{len(PART_FIELDS)} 个公共字段）")
        else:
            fail(f"CLOUD-{etype}", f"云端特有事件缺字段", f"missing: {missing}")


# ============================================================
# 主流程
# ============================================================

def main():
    print("=" * 70)
    print("WebSocket StreamMessage 实际对比（个人助手 vs 业务助手）")
    print("=" * 70)

    # Step 1: 启动 mock OpenCode agent
    print("\n[Step 1] 启动 Mock OpenCode Agent")
    agent = MockOpenCodeAgent(PERSONAL_AK, PERSONAL_SK)
    if agent.connect():
        ok("AGENT", f"Mock Agent 注册成功: ak={PERSONAL_AK}")
    else:
        fail("AGENT", "Mock Agent 注册失败")
        return

    time.sleep(1)

    # 业务助手也注册一个空 agent（仅用于通过 isAkOwnedByUser 权限检查）
    print("  注册业务助手空 Agent（权限校验需要）")
    biz_agent = MockOpenCodeAgent(BUSINESS_AK, "test-sk-business-secret")
    if biz_agent.connect():
        ok("BIZ-AGENT", f"业务助手 Agent 注册成功: ak={BUSINESS_AK}")
    else:
        fail("BIZ-AGENT", "业务助手 Agent 注册失败（权限校验将失败）")

    time.sleep(1)

    # Step 2: 创建两个 MiniApp 会话
    print("\n[Step 2] 创建 MiniApp 会话")

    # 个人助手会话
    resp1 = requests.post(f"{SS_URL}/api/skill/sessions",
        cookies={"userId": USER_ID}, json={"ak": PERSONAL_AK})
    if resp1.status_code == 200:
        d1 = resp1.json().get("data", resp1.json())
        personal_session = d1.get("welinkSessionId") or d1.get("id")
        ok("SESSION-P", f"个人助手会话: {personal_session}")
    else:
        fail("SESSION-P", f"创建失败 {resp1.status_code}")
        agent.close()
        return

    # 业务助手会话
    resp2 = requests.post(f"{SS_URL}/api/skill/sessions",
        cookies={"userId": USER_ID}, json={"ak": BUSINESS_AK})
    if resp2.status_code == 200:
        d2 = resp2.json().get("data", resp2.json())
        business_session = d2.get("welinkSessionId") or d2.get("id")
        ok("SESSION-B", f"业务助手会话: {business_session}")
    else:
        fail("SESSION-B", f"创建失败 {resp2.status_code}")
        agent.close()
        return

    time.sleep(2)

    # Step 3: 连接 WS 采集消息
    print("\n[Step 3] 连接 WS + 发送消息 + 采集 StreamMessage")

    all_messages = []
    ws_connected = threading.Event()

    ws = websocket.WebSocketApp(
        SS_WS_URL,
        cookie=f"userId={USER_ID}",
        on_message=lambda ws, msg: all_messages.append(json.loads(msg)),
        on_open=lambda ws: ws_connected.set(),
        on_error=lambda ws, e: None,
        on_close=lambda ws, c, m: None
    )
    wst = threading.Thread(target=ws.run_forever, daemon=True)
    wst.start()
    ws_connected.wait(timeout=5)
    time.sleep(2)  # 等 snapshot

    # 记录 snapshot 后的起点
    snapshot_count = len(all_messages)

    # 发消息给个人助手
    print("  发送消息给个人助手...")
    pre_personal = len(all_messages)
    resp_p = requests.post(f"{SS_URL}/api/skill/sessions/{personal_session}/messages",
        cookies={"userId": USER_ID}, json={"content": "hello personal"})
    print(f"  个人助手消息接口返回: {resp_p.status_code}")

    time.sleep(8)
    personal_msgs = [m for m in all_messages[pre_personal:]
                     if m.get("welinkSessionId") == personal_session]
    print(f"  个人助手收到 {len(personal_msgs)} 条 StreamMessage")

    # 发消息给业务助手
    print("  发送消息给业务助手...")
    pre_business = len(all_messages)
    resp_b = requests.post(f"{SS_URL}/api/skill/sessions/{business_session}/messages",
        cookies={"userId": USER_ID}, json={"content": "hello business"})
    print(f"  业务助手消息接口返回: {resp_b.status_code}")

    time.sleep(10)
    business_msgs = [m for m in all_messages[pre_business:]
                     if m.get("welinkSessionId") == business_session]
    print(f"  业务助手收到 {len(business_msgs)} 条 StreamMessage")

    ws.close()
    agent.close()
    try:
        biz_agent.close()
    except Exception:
        pass

    # Step 4: 对比
    if len(personal_msgs) == 0 and len(business_msgs) == 0:
        fail("COLLECT", "两个助手都未收到 StreamMessage（可能 MiniApp 消息接口权限问题）")
        print("\n  调试信息：")
        print(f"  个人助手消息接口: {resp_p.status_code} {resp_p.text[:100]}")
        print(f"  业务助手消息接口: {resp_b.status_code} {resp_b.text[:100]}")
        print(f"  WS 总消息数: {len(all_messages)}")
        all_types = [m.get("type") for m in all_messages[snapshot_count:]]
        print(f"  WS 事件类型（snapshot 后）: {all_types}")
    elif len(personal_msgs) == 0:
        fail("COLLECT-P", "个人助手未收到 StreamMessage")
        print(f"  个人助手接口: {resp_p.status_code}")
    elif len(business_msgs) == 0:
        fail("COLLECT-B", "业务助手未收到 StreamMessage")
        print(f"  业务助手接口: {resp_b.status_code}")
    else:
        ok("COLLECT", f"两者均收到消息（个人={len(personal_msgs)}, 业务={len(business_msgs)}）")
        compare_messages(personal_msgs, business_msgs)

    # 汇总
    print("\n" + "=" * 70)
    print(f"RESULT: PASS={passed}  FAIL={failed}  TOTAL={passed + failed}")
    print("=" * 70)

    sys.exit(1 if failed > 0 else 0)


if __name__ == "__main__":
    main()
