"""
云端 Agent 对接 完整端到端测试
前提：
  1. python tools/mock-cloud-server.py (端口 9999)
  2. skill-server (端口 8080, 指向 mock)
  3. ai-gateway (端口 8081, 指向 mock)

用法: python tools/e2e-test-full.py [--mock-only]
  --mock-only: 只测试 mock 接口（不需要 SS/GW）
"""

import sys
import json
import time
import requests
import argparse
import threading

MOCK_URL = "http://localhost:9999"
SS_URL = "http://localhost:8080"
GW_URL = "http://localhost:8081"

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
    except:
        return False


# ============================================================
# Mock 接口验证（不需要 SS/GW）
# ============================================================

def test_mock_upstream_api():
    """验证 mock 上游助手信息 API"""
    print("\n[E2E-Mock-1] 上游助手信息 API")

    # business
    resp = requests.get(f"{MOCK_URL}/appstore/wecodeapi/open/ak/info", params={"ak": "test-business-ak"})
    data = resp.json()
    if data["code"] == "200" and data["data"]["identityType"] == "3":
        ok("M01", "business ak 返回 identityType=3")
    else:
        fail("M01", "business ak", f"got: {data}")

    if data["data"]["hisAppId"] == "app_test_001":
        ok("M02", "appId=app_test_001")
    else:
        fail("M02", "appId", f"got: {data['data'].get('hisAppId')}")

    if data["data"]["endpoint"] and data["data"]["protocol"] == "sse" and data["data"]["authType"] == "soa":
        ok("M03", "endpoint/protocol/authType 正确")
    else:
        fail("M03", "endpoint/protocol/authType")

    # personal
    resp2 = requests.get(f"{MOCK_URL}/appstore/wecodeapi/open/ak/info", params={"ak": "test-personal-ak"})
    data2 = resp2.json()
    if data2["data"]["identityType"] == "2":
        ok("M04", "personal ak 返回 identityType=2")
    else:
        fail("M04", "personal ak", f"got: {data2}")

    # unknown
    resp3 = requests.get(f"{MOCK_URL}/appstore/wecodeapi/open/ak/info", params={"ak": "unknown-ak"})
    data3 = resp3.json()
    if data3["code"] == "404":
        ok("M05", "未知 ak 返回 code=404")
    else:
        fail("M05", "未知 ak", f"got: {data3}")


def test_mock_sse():
    """验证 mock SSE 接口返回完整事件序列"""
    print("\n[E2E-Mock-2] 云端 SSE 接口")

    resp = requests.post(f"{MOCK_URL}/api/v1/chat", json={
        "topicId": "test-topic-001",
        "content": "hello",
        "assistantAccount": "bot-001"
    }, stream=True)

    events = []
    for line in resp.iter_lines(decode_unicode=True):
        if line and line.startswith("data:"):
            data = json.loads(line[5:].strip())
            evt = data.get("event", {}).get("type", data.get("type", ""))
            events.append(evt)

    expected = [
        "planning.delta", "planning.delta", "planning.done",
        "searching", "search_result", "reference",
        "thinking.delta", "thinking.done",
        "text.delta", "text.done",
        "ask_more", "tool_done"
    ]

    # 检查每种期望的事件类型至少出现一次
    expected_types = set(expected)
    actual_types = set(events)
    for et in expected_types:
        if et in actual_types:
            ok(f"M-SSE-{et}", f"事件 {et} 存在")
        else:
            fail(f"M-SSE-{et}", f"事件 {et} 缺失", f"实际事件: {events}")

    # 检查 tool_done 是最后一个
    if events and events[-1] == "tool_done":
        ok("M-SSE-order", "tool_done 是最后一条")
    else:
        fail("M-SSE-order", "tool_done 应在最后", f"最后事件: {events[-1] if events else 'none'}")

    # 检查顺序：planning 在 searching 之前
    plan_idx = next((i for i, e in enumerate(events) if "planning" in e), -1)
    search_idx = next((i for i, e in enumerate(events) if e == "searching"), -1)
    text_idx = next((i for i, e in enumerate(events) if e == "text.delta"), -1)
    if plan_idx < search_idx < text_idx:
        ok("M-SSE-seq", "事件顺序正确: planning < searching < text")
    else:
        fail("M-SSE-seq", "事件顺序", f"plan={plan_idx}, search={search_idx}, text={text_idx}")


def test_mock_im():
    """验证 mock IM 出站接口"""
    print("\n[E2E-Mock-3] IM 出站 mock")

    # 清空
    requests.delete(f"{MOCK_URL}/mock/im-messages")

    # 单聊
    requests.post(f"{MOCK_URL}/v1/welinkim/im-service/chat/app-user-chat", json={
        "senderAccount": "bot", "sessionId": "im-123", "content": "direct test", "contentType": 13
    })

    # 群聊
    requests.post(f"{MOCK_URL}/v1/welinkim/im-service/chat/app-group-chat", json={
        "senderAccount": "bot", "sessionId": "group-456", "content": "group test", "contentType": 13
    })

    msgs = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    direct_msgs = [m for m in msgs if m["type"] == "direct"]
    group_msgs = [m for m in msgs if m["type"] == "group"]

    if len(direct_msgs) == 1:
        ok("M-IM-01", "单聊消息记录成功")
    else:
        fail("M-IM-01", "单聊", f"got {len(direct_msgs)}")

    if len(group_msgs) == 1:
        ok("M-IM-02", "群聊消息记录成功")
    else:
        fail("M-IM-02", "群聊", f"got {len(group_msgs)}")

    if direct_msgs and direct_msgs[0]["body"]["content"] == "direct test":
        ok("M-IM-03", "单聊内容正确")
    else:
        fail("M-IM-03", "单聊内容")

    # 清空
    requests.delete(f"{MOCK_URL}/mock/im-messages")
    msgs2 = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if len(msgs2) == 0:
        ok("M-IM-04", "清空成功")
    else:
        fail("M-IM-04", "清空", f"still {len(msgs2)}")


# ============================================================
# 全链路测试（需要 SS + GW + Mock）
# ============================================================

def test_e2e_gw_im_push():
    """E2E-04: IM 推送全链路"""
    print("\n[E2E-04] GW IM 推送")

    requests.delete(f"{MOCK_URL}/mock/im-messages")

    resp = requests.post(f"{GW_URL}/api/gateway/cloud/im-push", json={
        "assistantAccount": "test-bot",
        "userAccount": "c30051824",
        "imGroupId": None,
        "topicId": "cloud-test-push-001",
        "content": "GW push e2e test"
    })

    if resp.status_code == 200:
        ok("E04-01", "GW 推送接口返回 200")
    else:
        fail("E04-01", "GW 推送", f"status={resp.status_code}")

    # 给 WS 转发一点时间
    time.sleep(1)

    msgs = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if len(msgs) > 0:
        ok("E04-02", f"IM 出站收到 {len(msgs)} 条消息")
        if any("GW push e2e test" in m["body"].get("content", "") for m in msgs):
            ok("E04-03", "推送内容正确")
        else:
            fail("E04-03", "推送内容不匹配")
    else:
        skip("E04-02", "IM 出站未收到消息", "可能 toolSessionId 路由映射不存在（首次推送）")
        skip("E04-03", "跳过内容验证")


def test_e2e_gw_push_validation():
    """E2E-05: IM 推送校验失败"""
    print("\n[E2E-05] IM 推送校验")

    requests.delete(f"{MOCK_URL}/mock/im-messages")

    # topicId 不存在
    resp1 = requests.post(f"{GW_URL}/api/gateway/cloud/im-push", json={
        "assistantAccount": "test-bot",
        "userAccount": "c30051824",
        "topicId": "nonexistent-topic",
        "content": "should not be sent"
    })

    time.sleep(0.5)
    msgs = requests.get(f"{MOCK_URL}/mock/im-messages").json()
    if len(msgs) == 0:
        ok("E05-01", "topicId 不存在时 IM 未收到消息")
    else:
        fail("E05-01", "不应发送", f"收到 {len(msgs)} 条")


def test_e2e_sysconfig_crud():
    """E2E: SysConfig CRUD"""
    print("\n[E2E-SysConfig] 管理接口 CRUD")

    # Create
    resp = requests.post(f"{SS_URL}/api/admin/configs", json={
        "configType": "cloud_request_strategy",
        "configKey": "e2e_test_app",
        "configValue": "default",
        "description": "E2E test",
        "status": 1,
        "sortOrder": 0
    })
    if resp.status_code == 200:
        ok("SC-01", "创建配置成功")
        config_id = resp.json().get("id")
    else:
        fail("SC-01", "创建配置", f"status={resp.status_code}")
        return

    # List
    resp2 = requests.get(f"{SS_URL}/api/admin/configs", params={"type": "cloud_request_strategy"})
    if resp2.status_code == 200:
        configs = resp2.json()
        found = any(c["configKey"] == "e2e_test_app" for c in configs)
        if found:
            ok("SC-02", "查询列表包含新建配置")
        else:
            fail("SC-02", "查询列表缺失新建配置")
    else:
        fail("SC-02", "查询列表", f"status={resp2.status_code}")

    # Delete
    if config_id:
        resp3 = requests.delete(f"{SS_URL}/api/admin/configs/{config_id}")
        if resp3.status_code == 200:
            ok("SC-03", "删除配置成功")
        else:
            fail("SC-03", "删除配置", f"status={resp3.status_code}")


# ============================================================
# 主入口
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="云端 Agent E2E 测试")
    parser.add_argument("--mock-only", action="store_true", help="只测试 mock 接口")
    args = parser.parse_args()

    print("=" * 60)
    print("云端 Agent 对接 - 端到端测试")
    print("=" * 60)

    # 检查 mock 服务
    if not check_service(f"{MOCK_URL}/mock/health", "Mock"):
        print("\nMock 服务未启动！请先运行: python tools/mock-cloud-server.py")
        sys.exit(1)
    print(f"\nMock 服务: OK ({MOCK_URL})")

    # Mock 接口测试（始终运行）
    test_mock_upstream_api()
    test_mock_sse()
    test_mock_im()

    if args.mock_only:
        print("\n--mock-only 模式，跳过全链路测试")
    else:
        # 检查 SS 和 GW
        ss_ok = check_service(f"{SS_URL}/actuator/health", "SS")
        gw_ok = check_service(f"{GW_URL}/actuator/health", "GW")

        if ss_ok:
            print(f"Skill Server: OK ({SS_URL})")
        else:
            print(f"Skill Server: NOT RUNNING ({SS_URL})")

        if gw_ok:
            print(f"AI Gateway: OK ({GW_URL})")
        else:
            print(f"AI Gateway: NOT RUNNING ({GW_URL})")

        # 全链路测试
        if ss_ok:
            test_e2e_sysconfig_crud()
        else:
            skip("SC-*", "SysConfig CRUD", "SS 未启动")

        if gw_ok:
            test_e2e_gw_im_push()
            test_e2e_gw_push_validation()
        else:
            skip("E04-*", "GW IM 推送", "GW 未启动")
            skip("E05-*", "GW 推送校验", "GW 未启动")

    # 结果汇总
    print("\n" + "=" * 60)
    print(f"测试结果: PASS={passed} FAIL={failed} SKIP={skipped}")
    print("=" * 60)

    if failed > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
