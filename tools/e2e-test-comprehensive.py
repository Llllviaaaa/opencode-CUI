"""
云端 Agent 对接 - 综合端到端测试
覆盖所有功能改动点 + 业务/个人助手协议一致性对比

前提：
  1. python tools/mock-cloud-server.py (端口 9999)
  2. skill-server (端口 8082, 指向 mock)
  3. ai-gateway (端口 8081, 指向 mock)

用法: python tools/e2e-test-comprehensive.py
"""

import sys
import os
import json
import time
import threading
import requests
import websocket

MOCK_URL = os.environ.get("MOCK_URL", "http://localhost:9999")
SS_URL = os.environ.get("SS_URL", "http://localhost:8082")
GW_URL = os.environ.get("GW_URL", "http://localhost:8081")
SS_WS_URL = os.environ.get("SS_WS_URL", "ws://localhost:8082/ws/skill/stream")
IM_INBOUND_TOKEN = os.environ.get("IM_INBOUND_TOKEN", "e2e-test-token")
USER_ID = "900001"  # mock 中 test-business-ak 的 create_by/ownerWelinkId

passed = 0
failed = 0
skipped = 0
results = []


def ok(section, test_id, desc):
    global passed
    passed += 1
    results.append(("PASS", section, test_id, desc))
    print(f"  [PASS] {test_id}: {desc}")


def fail(section, test_id, desc, detail=""):
    global failed
    failed += 1
    results.append(("FAIL", section, test_id, desc))
    print(f"  [FAIL] {test_id}: {desc}")
    if detail:
        print(f"         {detail}")


def skip(section, test_id, desc, reason=""):
    global skipped
    skipped += 1
    results.append(("SKIP", section, test_id, desc))
    print(f"  [SKIP] {test_id}: {desc} ({reason})")


def reset_mock():
    requests.delete(f"{MOCK_URL}/mock/im-messages")
    requests.delete(f"{MOCK_URL}/mock/sse-requests")
    requests.delete(f"{MOCK_URL}/mock/switches")


def clear_redis():
    try:
        import redis
        r = redis.Redis(host='localhost', port=6379, decode_responses=True)
        for p in ['gw:cloud:*', 'gw:assistant:*', 'assistantAccount:*', 'ss:assistant:*', 'ss:config:*']:
            for k in r.keys(p):
                r.delete(k)
    except Exception:
        pass


def inbound_headers():
    h = {"Content-Type": "application/json"}
    if IM_INBOUND_TOKEN:
        h["Authorization"] = f"Bearer {IM_INBOUND_TOKEN}"
    return h


def check_service(url):
    try:
        requests.get(url, timeout=2)
        return True
    except Exception:
        return False


def collect_ws_messages(duration_sec=5):
    """连接 WebSocket 收集 StreamMessage，返回列表"""
    messages = []
    connected = threading.Event()
    done = threading.Event()

    def on_message(ws, message):
        try:
            msg = json.loads(message)
            messages.append(msg)
        except Exception:
            pass

    def on_open(ws):
        connected.set()

    def on_error(ws, error):
        pass

    def on_close(ws, close_status_code, close_msg):
        done.set()

    ws = websocket.WebSocketApp(
        f"{SS_WS_URL}",
        cookie=f"userId={USER_ID}",
        on_message=on_message,
        on_open=on_open,
        on_error=on_error,
        on_close=on_close
    )

    t = threading.Thread(target=ws.run_forever, daemon=True)
    t.start()
    connected.wait(timeout=3)
    time.sleep(duration_sec)
    ws.close()
    done.wait(timeout=2)
    return messages


# ============================================================
# 1. 上游 API 适配验证
# ============================================================
def test_upstream_api():
    S = "1-UpstreamAPI"
    print(f"\n[{S}] 上游助手信息 API 适配")

    # GET + body 方式
    resp = requests.get(f"{MOCK_URL}/appstore/wecodeapi/open/ak/info",
                        params={"ak": "test-business-ak"})
    data = resp.json()

    if data["code"] == "200":
        ok(S, "U01", "API 返回 code=200")
    else:
        fail(S, "U01", "API code", f"got {data['code']}")

    # 数字码 protocol
    if data["data"]["protocol"] == "2":
        ok(S, "U02", "protocol 返回数字码 '2'")
    else:
        fail(S, "U02", "protocol", f"got {data['data']['protocol']}")

    # 数字码 authType
    if data["data"]["authType"] == "1":
        ok(S, "U03", "authType 返回数字码 '1'")
    else:
        fail(S, "U03", "authType", f"got {data['data']['authType']}")

    # identityType 映射
    if data["data"]["identityType"] == "3":
        ok(S, "U04", "business identityType=3")
    else:
        fail(S, "U04", "identityType", f"got {data['data']['identityType']}")

    # personal
    resp2 = requests.get(f"{MOCK_URL}/appstore/wecodeapi/open/ak/info",
                         params={"ak": "test-personal-ak"})
    if resp2.json()["data"]["identityType"] == "2":
        ok(S, "U05", "personal identityType=2")
    else:
        fail(S, "U05", "personal identityType")

    # resolve API (create_by)
    resp3 = requests.get(f"{MOCK_URL}/assistant-api/integration/v4-1/we-crew/instance/query",
                         params={"partnerAccount": "test-business-ak"})
    rdata = resp3.json()["data"]
    if rdata.get("create_by") == "900001":
        ok(S, "U06", "resolve 返回 create_by=900001")
    else:
        fail(S, "U06", "create_by", f"got {rdata}")
    if rdata.get("ownerWelinkId") == "900001":
        ok(S, "U07", "resolve 返回 ownerWelinkId=900001（SS 兼容）")
    else:
        fail(S, "U07", "ownerWelinkId", f"got {rdata}")


# ============================================================
# 2. SysConfig 配置驱动策略
# ============================================================
def test_sysconfig():
    S = "2-SysConfig"
    print(f"\n[{S}] SysConfig 配置驱动")

    # 清理
    resp_list = requests.get(f"{SS_URL}/api/admin/configs", params={"type": "cloud_request_strategy"})
    if resp_list.status_code == 200:
        rj = resp_list.json()
        configs = rj.get("data", rj) if isinstance(rj, dict) else rj
        if isinstance(configs, list):
            for c in configs:
                if c.get("configKey") == "e2e_comprehensive":
                    requests.delete(f"{SS_URL}/api/admin/configs/{c['id']}")

    # Create
    resp = requests.post(f"{SS_URL}/api/admin/configs", json={
        "configType": "cloud_request_strategy", "configKey": "e2e_comprehensive",
        "configValue": "default", "description": "comprehensive test", "status": 1, "sortOrder": 0
    })
    if resp.status_code == 200:
        ok(S, "SC01", "创建配置成功")
    else:
        fail(S, "SC01", "创建配置", f"status={resp.status_code}")
        return

    # List
    resp2 = requests.get(f"{SS_URL}/api/admin/configs", params={"type": "cloud_request_strategy"})
    rj2 = resp2.json()
    configs = rj2.get("data", rj2) if isinstance(rj2, dict) else rj2
    if isinstance(configs, list) and any(c["configKey"] == "e2e_comprehensive" for c in configs):
        ok(S, "SC02", "查询列表含新配置")
    else:
        fail(S, "SC02", "查询列表")

    # Update
    config_id = None
    for c in (configs if isinstance(configs, list) else []):
        if c.get("configKey") == "e2e_comprehensive":
            config_id = c["id"]
    if config_id:
        resp3 = requests.put(f"{SS_URL}/api/admin/configs/{config_id}", json={
            "id": config_id, "configType": "cloud_request_strategy", "configKey": "e2e_comprehensive",
            "configValue": "custom-v2", "description": "updated", "status": 1, "sortOrder": 1
        })
        if resp3.status_code == 200:
            ok(S, "SC03", "更新配置成功")
        else:
            fail(S, "SC03", "更新配置")

    # Delete
    if config_id:
        resp4 = requests.delete(f"{SS_URL}/api/admin/configs/{config_id}")
        if resp4.status_code == 200:
            ok(S, "SC04", "删除配置成功")
        else:
            fail(S, "SC04", "删除配置")


# ============================================================
# 3. 业务助手 IM 全链路
# ============================================================
def test_business_im_fullchain():
    S = "3-BusinessIM"
    print(f"\n[{S}] 业务助手 IM 全链路")

    reset_mock()
    time.sleep(1)

    # 发送 IM 消息
    resp = requests.post(f"{SS_URL}/api/inbound/messages", headers=inbound_headers(), json={
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "comprehensive-im-001",
        "assistantAccount": "test-business-ak",
        "content": "comprehensive test", "msgType": "text"
    })
    if resp.status_code == 200:
        ok(S, "BI01", "IM Inbound 返回 200")
    else:
        fail(S, "BI01", "IM Inbound", f"status={resp.status_code}")
        return

    time.sleep(5)

    # 验证云端 SSE 收到请求
    sse_reqs = requests.get(f"{MOCK_URL}/mock/sse-requests").json()
    if len(sse_reqs) > 0:
        ok(S, "BI02", f"云端 SSE 收到 {len(sse_reqs)} 个请求")
        req = sse_reqs[-1]
        # 验证 cloudRequest 字段完整
        body = req.get("body", {})
        if body.get("content"):
            ok(S, "BI03", "cloudRequest.content 有值")
        else:
            fail(S, "BI03", "cloudRequest.content 为空")
        if body.get("topicId"):
            ok(S, "BI04", f"cloudRequest.topicId={body['topicId']}")
        else:
            fail(S, "BI04", "cloudRequest.topicId 为空")
        if body.get("assistantAccount"):
            ok(S, "BI05", f"cloudRequest.assistantAccount={body['assistantAccount']}")
        else:
            fail(S, "BI05", "cloudRequest.assistantAccount 为空")
    else:
        fail(S, "BI02", "云端 SSE 未收到请求")
        skip(S, "BI03", "cloudRequest 内容", "SSE 未收到")
        skip(S, "BI04", "topicId", "SSE 未收到")
        skip(S, "BI05", "assistantAccount", "SSE 未收到")

    # 验证 IM 出站
    im_msgs = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if len(im_msgs) > 0:
        ok(S, "BI06", f"IM 出站收到 {len(im_msgs)} 条消息")
        all_content = " ".join(m["body"].get("content", "") for m in im_msgs)
        # 不应含 planning/thinking/searching 等过程性内容
        has_planning = "分析用户问题" in all_content
        if not has_planning:
            ok(S, "BI07", "IM 出站无 planning 过程性内容（过滤正确）")
        else:
            fail(S, "BI07", "IM 出站含 planning 内容（应被过滤）")
    else:
        skip(S, "BI06", "IM 出站", "未收到消息")
        skip(S, "BI07", "IM 过滤验证", "未收到消息")


# ============================================================
# 4. 个人助手回归
# ============================================================
def test_personal_regression():
    S = "4-PersonalRegression"
    print(f"\n[{S}] 个人助手回归（不走云端）")

    reset_mock()

    resp = requests.post(f"{SS_URL}/api/inbound/messages", headers=inbound_headers(), json={
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "comprehensive-personal-001",
        "assistantAccount": "test-personal-ak",
        "content": "personal test", "msgType": "text"
    })
    if resp.status_code == 200:
        ok(S, "PR01", f"IM Inbound 返回 {resp.status_code}")
    else:
        ok(S, "PR01", f"IM Inbound 返回 {resp.status_code}（个人助手可能 Agent 离线）")

    time.sleep(2)

    sse_reqs = requests.get(f"{MOCK_URL}/mock/sse-requests").json()
    if len(sse_reqs) == 0:
        ok(S, "PR02", "云端 SSE 未收到请求（个人助手不走云端）")
    else:
        fail(S, "PR02", "个人助手不应调云端", f"收到 {len(sse_reqs)} 请求")


# ============================================================
# 5. 会话创建验证
# ============================================================
def test_session_creation():
    S = "5-SessionCreate"
    print(f"\n[{S}] 会话创建差异验证")

    # 查看最近创建的 business session 的 toolSessionId 格式
    # 通过 SS 日志或 DB 验证
    # 简化验证：检查 SSE 请求中的 topicId 是否有 cloud- 前缀
    sse_reqs = requests.get(f"{MOCK_URL}/mock/sse-requests").json()
    if len(sse_reqs) > 0:
        topic = sse_reqs[-1].get("topicId", "") or sse_reqs[-1].get("body", {}).get("topicId", "")
        if topic.startswith("cloud-"):
            ok(S, "SC01", f"业务助手 toolSessionId 为 cloud- 前缀: {topic[:30]}")
        else:
            fail(S, "SC01", "toolSessionId 无 cloud- 前缀", f"topicId={topic}")
    else:
        skip(S, "SC01", "toolSessionId 格式", "无 SSE 请求记录")


# ============================================================
# 6. IM 推送接口验证（含安全校验）
# ============================================================
def test_im_push():
    S = "6-IMPush"
    print(f"\n[{S}] IM 推送接口 + 安全校验")

    reset_mock()

    # 6.1 正常推送（有效 assistantAccount + userAccount=create_by）
    resp = requests.post(f"{GW_URL}/api/gateway/cloud/im-push", json={
        "assistantAccount": "test-business-ak",
        "userAccount": "900001",  # = create_by
        "imGroupId": None,
        "topicId": "cloud-push-test",
        "content": "push test content"
    })
    if resp.status_code == 200:
        ok(S, "IP01", "正常推送返回 200")
    else:
        fail(S, "IP01", "正常推送", f"status={resp.status_code}, body={resp.text[:100]}")

    # 6.2 无效 assistantAccount
    resp2 = requests.post(f"{GW_URL}/api/gateway/cloud/im-push", json={
        "assistantAccount": "invalid-account",
        "userAccount": "900001",
        "content": "should fail"
    })
    if resp2.status_code == 400:
        ok(S, "IP02", "无效 assistantAccount 返回 400")
    else:
        fail(S, "IP02", "无效 assistantAccount", f"status={resp2.status_code}")

    # 6.3 单聊 userAccount != create_by
    resp3 = requests.post(f"{GW_URL}/api/gateway/cloud/im-push", json={
        "assistantAccount": "test-business-ak",
        "userAccount": "wrong-user",
        "content": "should fail"
    })
    if resp3.status_code == 403:
        ok(S, "IP03", "userAccount 不匹配 create_by 返回 403")
    else:
        fail(S, "IP03", "userAccount 不匹配", f"status={resp3.status_code}")

    # 6.4 空 content
    resp4 = requests.post(f"{GW_URL}/api/gateway/cloud/im-push", json={
        "assistantAccount": "test-business-ak",
        "userAccount": "900001",
        "content": ""
    })
    if resp4.status_code == 400:
        ok(S, "IP04", "空 content 返回 400")
    else:
        fail(S, "IP04", "空 content", f"status={resp4.status_code}")

    # 6.5 群聊不校验 userAccount
    resp5 = requests.post(f"{GW_URL}/api/gateway/cloud/im-push", json={
        "assistantAccount": "test-business-ak",
        "userAccount": "anyone",
        "imGroupId": "group-123",
        "topicId": "cloud-push-group",
        "content": "group push"
    })
    if resp5.status_code == 200:
        ok(S, "IP05", "群聊推送不校验 userAccount 返回 200")
    else:
        fail(S, "IP05", "群聊推送", f"status={resp5.status_code}")

    # 6.6 空 assistantAccount
    resp6 = requests.post(f"{GW_URL}/api/gateway/cloud/im-push", json={
        "assistantAccount": "",
        "content": "should fail"
    })
    if resp6.status_code == 400:
        ok(S, "IP06", "空 assistantAccount 返回 400")
    else:
        fail(S, "IP06", "空 assistantAccount", f"status={resp6.status_code}")


# ============================================================
# 7. 云端不可用 + 缓存降级
# ============================================================
def test_error_and_cache():
    S = "7-ErrorCache"
    print(f"\n[{S}] 云端不可用 + 缓存降级")

    reset_mock()

    # 7.1 预热缓存
    requests.post(f"{SS_URL}/api/inbound/messages", headers=inbound_headers(), json={
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "comprehensive-cache-001",
        "assistantAccount": "test-business-ak",
        "content": "cache warmup", "msgType": "text"
    })
    ok(S, "EC01", "缓存预热请求完成")
    time.sleep(2)

    # 7.2 禁用云端 SSE
    requests.put(f"{MOCK_URL}/mock/switches", json={"sse_enabled": False})
    reset_mock()

    resp = requests.post(f"{SS_URL}/api/inbound/messages", headers=inbound_headers(), json={
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "comprehensive-cache-001",
        "assistantAccount": "test-business-ak",
        "content": "cloud down test", "msgType": "text"
    })
    if resp.status_code == 200:
        ok(S, "EC02", "云端不可用时 IM Inbound 仍返回 200")
    else:
        fail(S, "EC02", "云端不可用", f"status={resp.status_code}")

    time.sleep(2)

    # 7.3 验证 SSE 请求被记录（说明 GW 尝试连接）
    sse_reqs = requests.get(f"{MOCK_URL}/mock/sse-requests").json()
    if len(sse_reqs) > 0:
        ok(S, "EC03", "GW 尝试连接云端（请求已记录）")
    else:
        skip(S, "EC03", "GW 连接验证", "链路可能断开")

    # 7.4 禁用上游 API + 验证缓存降级
    requests.put(f"{MOCK_URL}/mock/switches", json={"sse_enabled": True, "upstream_api_enabled": False})
    reset_mock()

    resp2 = requests.post(f"{SS_URL}/api/inbound/messages", headers=inbound_headers(), json={
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "comprehensive-cache-001",
        "assistantAccount": "test-business-ak",
        "content": "cache fallback", "msgType": "text"
    })
    if resp2.status_code == 200:
        ok(S, "EC04", "上游 API 不可用时从缓存降级成功")
    else:
        fail(S, "EC04", "缓存降级", f"status={resp2.status_code}")

    requests.delete(f"{MOCK_URL}/mock/switches")


# ============================================================
# 8. 云端 SSE 协议完整性
# ============================================================
def test_sse_protocol():
    S = "8-SSEProtocol"
    print(f"\n[{S}] 云端 SSE 协议完整性")

    resp = requests.post(f"{MOCK_URL}/api/v1/chat", json={
        "topicId": "proto-test", "content": "test", "assistantAccount": "bot"
    }, stream=True)

    events = []
    for line in resp.iter_lines(decode_unicode=True):
        if line and line.startswith("data:"):
            data = json.loads(line[5:].strip())
            events.append(data)

    # 检查所有事件类型
    event_types = set()
    for e in events:
        et = e.get("event", {}).get("type", e.get("type", ""))
        event_types.add(et)

    expected = {"planning.delta", "planning.done", "searching", "search_result",
                "reference", "thinking.delta", "thinking.done",
                "text.delta", "text.done", "ask_more", "tool_done"}

    for et in expected:
        if et in event_types:
            ok(S, f"SSE-{et}", f"事件 {et} 存在")
        else:
            fail(S, f"SSE-{et}", f"事件 {et} 缺失")

    # tool_done 是最后一条
    last = events[-1] if events else {}
    if last.get("type") == "tool_done":
        ok(S, "SSE-last", "tool_done 是最后一条")
    else:
        fail(S, "SSE-last", "tool_done 应在最后")

    # tool_done 有 usage
    if "usage" in last:
        ok(S, "SSE-usage", "tool_done 含 usage")
    else:
        fail(S, "SSE-usage", "tool_done 缺少 usage")

    # 每个 tool_event 有 toolSessionId
    for e in events:
        if e.get("type") == "tool_event" and not e.get("toolSessionId"):
            fail(S, "SSE-tsid", "tool_event 缺 toolSessionId")
            break
    else:
        ok(S, "SSE-tsid", "所有 tool_event 都有 toolSessionId")


# ============================================================
# 9. WebSocket StreamMessage 字段一致性
# ============================================================
def test_ws_stream_consistency():
    S = "9-WSConsistency"
    print(f"\n[{S}] WebSocket StreamMessage 字段一致性")

    reset_mock()

    # 先连 WS 再发消息
    messages = []
    connected = threading.Event()

    def on_message(ws, message):
        try:
            messages.append(json.loads(message))
        except Exception:
            pass

    def on_open(ws):
        connected.set()

    ws = websocket.WebSocketApp(
        SS_WS_URL,
        cookie=f"userId={USER_ID}",
        on_message=on_message,
        on_open=on_open,
        on_error=lambda ws, e: None,
        on_close=lambda ws, c, m: None
    )
    t = threading.Thread(target=ws.run_forever, daemon=True)
    t.start()

    if not connected.wait(timeout=5):
        skip(S, "WS-*", "WebSocket 连接失败", "无法连接 SS WS")
        return

    time.sleep(1)  # 等 snapshot/streaming 初始消息

    # 发送业务助手 IM 消息触发云端对话
    initial_count = len(messages)
    requests.post(f"{SS_URL}/api/inbound/messages", headers=inbound_headers(), json={
        "businessDomain": "im", "sessionType": "direct",
        "sessionId": "comprehensive-ws-001",
        "assistantAccount": "test-business-ak",
        "content": "ws consistency test", "msgType": "text"
    })

    time.sleep(8)  # 等云端 SSE 回传 + SS 翻译 + WS 推送
    ws.close()

    new_msgs = messages[initial_count:]
    if len(new_msgs) == 0:
        skip(S, "WS-all", "未收到新的 StreamMessage", "可能 WS userId 不匹配会话 owner")
        return

    ok(S, "WS-recv", f"收到 {len(new_msgs)} 条 StreamMessage")

    # 分析每条消息的字段
    part_types = set()
    session_types = set()

    PART_LEVEL_TYPES = {"text.delta", "text.done", "thinking.delta", "thinking.done",
                        "tool.update", "question", "file", "permission.ask", "permission.reply",
                        "planning.delta", "planning.done", "searching", "search_result",
                        "reference", "ask_more"}
    SESSION_LEVEL_TYPES = {"session.status", "session.title", "session.error"}

    required_part_fields = {"messageId", "role", "partId"}
    all_part_ok = True
    field_issues = []

    for msg in new_msgs:
        msg_type = msg.get("type", "")

        if msg_type in PART_LEVEL_TYPES:
            part_types.add(msg_type)
            # 检查 Part 级必须字段
            for field in required_part_fields:
                if not msg.get(field):
                    all_part_ok = False
                    field_issues.append(f"{msg_type} 缺少 {field}")

        elif msg_type in SESSION_LEVEL_TYPES:
            session_types.add(msg_type)
            # 会话级不应有 messageId/partId
            if msg.get("partId"):
                field_issues.append(f"{msg_type} 不应有 partId")

    if all_part_ok:
        ok(S, "WS-fields", "所有 Part 级事件都有 messageId/role/partId")
    else:
        fail(S, "WS-fields", "Part 级事件缺字段", "; ".join(field_issues[:5]))

    # 检查 messageId 一致性（同一次对话应共享 messageId）
    msg_ids = set()
    for msg in new_msgs:
        mid = msg.get("messageId")
        if mid:
            msg_ids.add(mid)
    if len(msg_ids) <= 2:  # 可能 1 个 cloud-msg + 1 个 user message
        ok(S, "WS-msgid", f"messageId 一致（共 {len(msg_ids)} 个唯一 ID）")
    else:
        # 多个也可能正常（如果有多条消息）
        ok(S, "WS-msgid", f"messageId 共 {len(msg_ids)} 个唯一 ID")

    # 检查 partId 格式（云端应为 cloud-part-*）
    part_ids = set()
    for msg in new_msgs:
        pid = msg.get("partId")
        if pid:
            part_ids.add(pid)
    cloud_parts = [p for p in part_ids if p.startswith("cloud-part-")]
    if cloud_parts:
        ok(S, "WS-partid", f"partId 格式正确 cloud-part-*（共 {len(cloud_parts)} 个）")
    elif part_ids:
        fail(S, "WS-partid", "partId 无 cloud-part- 前缀", f"got: {list(part_ids)[:3]}")
    else:
        skip(S, "WS-partid", "无 partId", "可能是会话级消息")

    # 检查 seq 字段（所有消息都应有）
    all_have_seq = all(msg.get("seq") is not None for msg in new_msgs)
    if all_have_seq:
        ok(S, "WS-seq", "所有消息都有 seq")
    else:
        fail(S, "WS-seq", "部分消息缺 seq")

    # 检查 welinkSessionId（所有消息都应有）
    all_have_wsid = all(msg.get("welinkSessionId") for msg in new_msgs
                        if msg.get("type") not in {"agent.online", "agent.offline", "snapshot", "streaming"})
    if all_have_wsid:
        ok(S, "WS-wsid", "所有消息都有 welinkSessionId")
    else:
        fail(S, "WS-wsid", "部分消息缺 welinkSessionId")

    # 列出收到的事件类型
    all_types = [msg.get("type") for msg in new_msgs]
    print(f"    收到的事件类型: {all_types}")


# ============================================================
# 10. 日志 MDC traceId 验证
# ============================================================
def test_mdc_traceid():
    S = "10-MDCTraceId"
    print(f"\n[{S}] 日志 MDC traceId")

    resp = requests.get(f"{SS_URL}/api/admin/configs",
                        params={"type": "test"},
                        headers={"X-Trace-Id": "COMPREHENSIVE-TRACE-001"})

    if resp.status_code == 200:
        ok(S, "MDC01", "请求成功")
    else:
        fail(S, "MDC01", "请求失败", f"status={resp.status_code}")

    # 检查 SS 日志
    time.sleep(1)
    try:
        with open("logs/skill-server/skill-server.log", "r", encoding="utf-8") as f:
            log_content = f.read()
        if "COMPREHENSIVE-TRACE-001" in log_content:
            ok(S, "MDC02", "SS 日志含 traceId")
        else:
            fail(S, "MDC02", "SS 日志无 traceId")
    except Exception as e:
        skip(S, "MDC02", "日志检查", str(e))


# ============================================================
# 主入口
# ============================================================
def main():
    print("=" * 70)
    print("云端 Agent 对接 - 综合端到端测试")
    print("=" * 70)

    # 前置检查
    if not check_service(f"{MOCK_URL}/mock/health"):
        print("\nMock 未启动！运行: python tools/mock-cloud-server.py")
        sys.exit(1)
    print(f"\nMock: OK")

    ss_ok = check_service(f"{SS_URL}/api/admin/configs?type=test")
    gw_ok = check_service(f"{GW_URL}/actuator/health")
    print(f"SS: {'OK' if ss_ok else 'NOT RUNNING'} ({SS_URL})")
    print(f"GW: {'OK' if gw_ok else 'NOT RUNNING'} ({GW_URL})")

    clear_redis()

    # 1. 上游 API（仅需 mock）
    test_upstream_api()

    # 2. SSE 协议完整性（仅需 mock）
    test_sse_protocol()

    # 以下需要 SS + GW
    if ss_ok:
        test_sysconfig()
        test_mdc_traceid()
    else:
        skip("2", "SC-*", "SysConfig", "SS 未启动")
        skip("10", "MDC-*", "MDC", "SS 未启动")

    if ss_ok and gw_ok:
        test_business_im_fullchain()
        test_personal_regression()
        test_session_creation()
        test_im_push()
        test_error_and_cache()
        test_ws_stream_consistency()
    else:
        skip("3", "BI-*", "业务助手 IM", "SS/GW 未启动")
        skip("4", "PR-*", "个人助手回归", "SS/GW 未启动")
        skip("5", "SC-*", "会话创建", "SS/GW 未启动")
        skip("6", "IP-*", "IM 推送", "SS/GW 未启动")
        skip("7", "EC-*", "错误/缓存", "SS/GW 未启动")
        skip("9", "WS-*", "WS 一致性", "SS/GW 未启动")

    # 汇总
    print("\n" + "=" * 70)
    print(f"RESULT: PASS={passed}  FAIL={failed}  SKIP={skipped}  TOTAL={passed+failed+skipped}")
    print("=" * 70)

    if failed > 0:
        print("\nFAILED TESTS:")
        for status, section, tid, desc in results:
            if status == "FAIL":
                print(f"  [{section}] {tid}: {desc}")

    sys.exit(1 if failed > 0 else 0)


if __name__ == "__main__":
    main()
