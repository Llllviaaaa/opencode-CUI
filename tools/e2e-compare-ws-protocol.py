"""
MiniApp WS vs External WS 协议对比验证
同时连接两个 WS 端点，发同一条消息，逐条对比两边收到的报文字段是否一致。

前提：
  1. skill-server 运行中 (端口 8082)
  2. ai-gateway 运行中 (端口 8081)
  3. OpenCode agent 在线 (test-ak-001)

用法: python tools/e2e-compare-ws-protocol.py
"""

import websocket
import json
import base64
import time
import threading
import os
import requests

SS_URL = os.environ.get("SS_URL", "http://localhost:8082")
TOKEN = os.environ.get("IM_INBOUND_TOKEN", "e2e-test-token")
AGENT_AK = os.environ.get("REAL_AGENT_AK", "test-ak-001")
# miniapp WS 用 userId cookie 认证，external WS 用 subprotocol 认证
# agent 的 userId = 1（从 GW 注册信息得知）
USER_ID = "1"

MINIAPP_WS_URL = SS_URL.replace("http://", "ws://") + "/ws/skill/stream"
EXTERNAL_WS_URL = SS_URL.replace("http://", "ws://") + "/ws/external/stream"


def connect_miniapp_ws():
    """连接 miniapp WS（Cookie userId 认证）"""
    ws = websocket.WebSocket()
    ws.settimeout(3)
    ws.connect(MINIAPP_WS_URL, cookie=f"userId={USER_ID}")
    return ws


def connect_external_ws():
    """连接 external WS（Sec-WebSocket-Protocol 认证）"""
    auth = json.dumps({"token": TOKEN, "source": "im", "instanceId": "compare-ext"})
    proto = "auth." + base64.urlsafe_b64encode(auth.encode()).decode().rstrip("=")
    ws = websocket.WebSocket()
    ws.settimeout(3)
    ws.connect(EXTERNAL_WS_URL, subprotocols=[proto])
    return ws


def collect_messages(ws, timeout, label):
    """收集 WS 消息，返回按 type 分组的 {type: [msg, ...]}"""
    messages = []
    start = time.time()
    last_ping = time.time()
    while time.time() - start < timeout:
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
                # miniapp 和 external 都直接收到 StreamMessage（无信封）
                # 但 miniapp 的 resume 可能收到 snapshot/streaming，跳过
                if m.get("action") == "pong":
                    continue
                messages.append(m)
        except websocket.WebSocketTimeoutException:
            continue
        except Exception:
            break
    return messages


def send_chat(session_id):
    """通过 external invoke 发消息"""
    return requests.post(f"{SS_URL}/api/external/invoke",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        json={
            "action": "chat",
            "businessDomain": "im",
            "sessionType": "direct",
            "sessionId": session_id,
            "assistantAccount": AGENT_AK,
            "payload": {"content": "Reply: COMPARE_WS_TEST", "msgType": "text"}
        })


def group_by_type(messages):
    """按 type 分组，每种取第一个"""
    result = {}
    for m in messages:
        t = m.get("type", "?")
        if t not in result:
            result[t] = m
    return result


def compare_fields(miniapp_msg, external_msg, msg_type):
    """对比两个消息的字段 key 集合"""
    m_keys = sorted(k for k, v in miniapp_msg.items() if v is not None)
    e_keys = sorted(k for k, v in external_msg.items() if v is not None)

    if m_keys == e_keys:
        return True, m_keys, e_keys, [], []
    else:
        only_m = sorted(set(m_keys) - set(e_keys))
        only_e = sorted(set(e_keys) - set(m_keys))
        return False, m_keys, e_keys, only_m, only_e


def main():
    print("=" * 70)
    print("MiniApp WS vs External WS Protocol Comparison")
    print("=" * 70)

    # 1. 预热 session
    session_id = f"compare-ws-{int(time.time())}"
    print(f"\nSession: {session_id}")
    print("Warming up session...")
    send_chat(session_id)
    time.sleep(30)
    print("Warmup done.")

    # 2. 连接 external WS（用于收 IM domain 消息）
    print("\nConnecting External WS...")
    try:
        ws_external = connect_external_ws()
        print(f"  External WS: connected ({EXTERNAL_WS_URL})")
    except Exception as e:
        print(f"  External WS: FAILED ({e})")
        return

    # 3. 连接 miniapp WS（用于收 miniapp domain 消息）
    print("Connecting Miniapp WS...")
    try:
        ws_miniapp = connect_miniapp_ws()
        print(f"  Miniapp WS: connected ({MINIAPP_WS_URL})")
    except Exception as e:
        print(f"  Miniapp WS: FAILED ({e})")
        ws_external.close()
        return

    # 消耗 miniapp 的初始 snapshot/streaming 推送
    time.sleep(3)
    try:
        while True:
            ws_miniapp.recv()
    except Exception:
        pass

    # 3. 发消息通过 IM domain（external 收到）
    print("\nSending IM domain chat (external WS should receive)...")
    send_chat(session_id)

    # 同时通过 miniapp API 发消息（miniapp WS 收到）
    # 使用 SkillMessageController 的 sendMessage 接口
    miniapp_session_id = None
    sessions = requests.get(f"{SS_URL}/api/skill/sessions",
        headers={"Cookie": f"userId={USER_ID}"}).json()
    miniapp_sessions = [s for s in sessions.get("data", {}).get("content", [])
                        if s.get("businessSessionDomain") == "miniapp" and s.get("toolSessionId")]
    if miniapp_sessions:
        miniapp_session_id = str(miniapp_sessions[0].get("welinkSessionId", miniapp_sessions[0].get("id")))
        print(f"Sending miniapp chat to existing session: {miniapp_session_id}")
        requests.post(f"{SS_URL}/api/skill/sessions/{miniapp_session_id}/messages",
            headers={"Cookie": f"userId={USER_ID}", "Content-Type": "application/json"},
            json={"content": "Reply: COMPARE_MINIAPP_TEST"})
    else:
        print("No miniapp session found, creating one...")
        resp = requests.post(f"{SS_URL}/api/skill/sessions",
            headers={"Cookie": f"userId={USER_ID}", "Content-Type": "application/json"},
            json={"ak": AGENT_AK})
        if resp.status_code == 200:
            data = resp.json().get("data", resp.json())
            miniapp_session_id = str(data.get("welinkSessionId", data.get("id", "")))
            print(f"Created miniapp session: {miniapp_session_id}")
            time.sleep(3)
            requests.post(f"{SS_URL}/api/skill/sessions/{miniapp_session_id}/messages",
                headers={"Cookie": f"userId={USER_ID}", "Content-Type": "application/json"},
                json={"content": "Reply: COMPARE_MINIAPP_TEST"})
        else:
            print(f"Failed to create miniapp session: {resp.status_code}")

    # 4. 并行收集两边消息
    miniapp_msgs = []
    external_msgs = []

    def collect_miniapp():
        nonlocal miniapp_msgs
        miniapp_msgs = collect_messages(ws_miniapp, 25, "miniapp")

    def collect_external():
        nonlocal external_msgs
        external_msgs = collect_messages(ws_external, 25, "external")

    t1 = threading.Thread(target=collect_miniapp, daemon=True)
    t2 = threading.Thread(target=collect_external, daemon=True)
    t1.start()
    t2.start()
    t1.join(timeout=30)
    t2.join(timeout=30)

    ws_miniapp.close()
    ws_external.close()

    print(f"\nMiniapp received: {len(miniapp_msgs)} messages")
    print(f"External received: {len(external_msgs)} messages")

    # 5. 按 type 分组对比
    m_grouped = group_by_type(miniapp_msgs)
    e_grouped = group_by_type(external_msgs)

    all_types = sorted(set(list(m_grouped.keys()) + list(e_grouped.keys())))

    print(f"\nTypes found: {len(all_types)}")
    print()
    print("-" * 70)

    passed = 0
    failed = 0
    skipped = 0

    for t in all_types:
        if t in m_grouped and t in e_grouped:
            match, mk, ek, only_m, only_e = compare_fields(m_grouped[t], e_grouped[t], t)
            if match:
                print(f"[MATCH] {t}")
                print(f"  fields: {mk}")
                passed += 1
            else:
                print(f"[DIFF]  {t}")
                print(f"  miniapp:  {mk}")
                print(f"  external: {ek}")
                if only_m:
                    print(f"  ONLY miniapp:  {only_m}")
                if only_e:
                    print(f"  ONLY external: {only_e}")
                failed += 1
        elif t in m_grouped:
            print(f"[ONLY_MINIAPP] {t}")
            print(f"  fields: {sorted(k for k, v in m_grouped[t].items() if v is not None)}")
            skipped += 1
        else:
            print(f"[ONLY_EXTERNAL] {t}")
            print(f"  fields: {sorted(k for k, v in e_grouped[t].items() if v is not None)}")
            skipped += 1
        print()

    # 6. 逐字段类型对比（对 text.done 做深度检查）
    if "text.done" in m_grouped and "text.done" in e_grouped:
        print("-" * 70)
        print("Deep compare: text.done field types")
        print("-" * 70)
        mm = m_grouped["text.done"]
        em = e_grouped["text.done"]
        all_keys = sorted(set(list(mm.keys()) + list(em.keys())))
        for k in all_keys:
            mv = mm.get(k)
            ev = em.get(k)
            mt = type(mv).__name__ if mv is not None else "null"
            et = type(ev).__name__ if ev is not None else "null"
            type_match = "OK" if mt == et else "TYPE_DIFF"
            print(f"  [{type_match}] {k}: miniapp={mt}, external={et}")
        print()

    # 7. 结果
    print("=" * 70)
    total = passed + failed + skipped
    print(f"Result: MATCH={passed} DIFF={failed} ONE_SIDE_ONLY={skipped} TOTAL={total}")
    if failed > 0:
        print("VERDICT: PROTOCOL NOT CONSISTENT")
    elif skipped > 0:
        print("VERDICT: CONSISTENT (for shared types)")
    else:
        print("VERDICT: FULLY CONSISTENT")
    print("=" * 70)

    if failed > 0:
        exit(1)


if __name__ == "__main__":
    main()
