"""
MiniApp WS vs External WS 协议对比验证
分两轮测试：
  1. 通过 miniapp API 发消息 → miniapp WS 收集
  2. 通过 external invoke 发消息 → external WS 收集
两轮用同一个 agent，对比收到的各类型消息字段是否一致。

前提：
  1. skill-server (8082) + ai-gateway (8081) + agent (test-ak-001) 在线
  2. agent 关闭 bash auto-approve（触发 permission.ask）

用法: python tools/e2e-compare-ws-protocol.py
"""

import websocket
import json
import base64
import time
import os
import requests

SS_URL = os.environ.get("SS_URL", "http://localhost:8082")
TOKEN = os.environ.get("IM_INBOUND_TOKEN", "e2e-test-token")
AGENT_AK = os.environ.get("REAL_AGENT_AK", "test-ak-001")
USER_ID = "1"
WAIT = int(os.environ.get("AGENT_WAIT_SECONDS", "35"))

MINIAPP_WS_URL = SS_URL.replace("http://", "ws://") + "/ws/skill/stream"
EXTERNAL_WS_URL = SS_URL.replace("http://", "ws://") + "/ws/external/stream"


def connect_miniapp():
    ws = websocket.WebSocket()
    ws.settimeout(3)
    ws.connect(MINIAPP_WS_URL, cookie=f"userId={USER_ID}")
    return ws


def connect_external():
    auth = json.dumps({"token": TOKEN, "source": "im", "instanceId": "cmp-ext"})
    proto = "auth." + base64.urlsafe_b64encode(auth.encode()).decode().rstrip("=")
    ws = websocket.WebSocket()
    ws.settimeout(3)
    ws.connect(EXTERNAL_WS_URL, subprotocols=[proto])
    return ws


def collect(ws, timeout):
    msgs = []
    start = time.time()
    lp = time.time()
    while time.time() - start < timeout:
        if time.time() - lp > 10:
            try:
                ws.send(json.dumps({"action": "ping"}))
                lp = time.time()
            except Exception:
                pass
        try:
            raw = ws.recv()
            if raw:
                m = json.loads(raw)
                if m.get("action") == "pong":
                    continue
                msgs.append(m)
        except websocket.WebSocketTimeoutException:
            continue
        except Exception:
            break
    return msgs


def drain(ws):
    """排空 WS 残留消息"""
    try:
        while True:
            ws.recv()
    except Exception:
        pass


def group_by_type(messages):
    result = {}
    for m in messages:
        t = m.get("type", "?")
        if t not in result:
            result[t] = m
    return result


def non_null_keys(msg):
    return sorted(k for k, v in msg.items() if v is not None)


def get_or_create_miniapp_session():
    """获取或创建一个 miniapp session"""
    sessions = requests.get(f"{SS_URL}/api/skill/sessions",
                            headers={"Cookie": f"userId={USER_ID}"}).json()
    existing = [s for s in sessions.get("data", {}).get("content", [])
                if s.get("businessSessionDomain") == "miniapp" and s.get("toolSessionId")]
    if existing:
        sid = str(existing[0].get("welinkSessionId", existing[0].get("id")))
        return sid

    resp = requests.post(f"{SS_URL}/api/skill/sessions",
                         headers={"Cookie": f"userId={USER_ID}", "Content-Type": "application/json"},
                         json={"ak": AGENT_AK})
    if resp.status_code == 200:
        data = resp.json().get("data", resp.json())
        sid = str(data.get("welinkSessionId", data.get("id", "")))
        time.sleep(5)
        return sid
    return None


def main():
    print("=" * 70)
    print("MiniApp WS vs External WS Protocol Comparison")
    print("=" * 70)

    # ============================================
    # Round 1: miniapp path
    # ============================================
    print("\n--- Round 1: MiniApp path ---")
    miniapp_sid = get_or_create_miniapp_session()
    if not miniapp_sid:
        print("FAILED: cannot get miniapp session")
        return
    print(f"Miniapp session: {miniapp_sid}")

    ws_m = connect_miniapp()
    print("Miniapp WS connected")
    drain(ws_m)  # 排空 snapshot 等初始消息

    # 发普通消息
    print("Sending chat via miniapp API...")
    requests.post(f"{SS_URL}/api/skill/sessions/{miniapp_sid}/messages",
                  headers={"Cookie": f"userId={USER_ID}", "Content-Type": "application/json"},
                  json={"content": "Reply: MINIAPP_COMPARE_TEST"})
    miniapp_msgs = collect(ws_m, WAIT)
    print(f"Collected {len(miniapp_msgs)} messages")

    # 发触发 permission 的消息
    print("Sending permission-triggering chat via miniapp API...")
    requests.post(f"{SS_URL}/api/skill/sessions/{miniapp_sid}/messages",
                  headers={"Cookie": f"userId={USER_ID}", "Content-Type": "application/json"},
                  json={"content": "Run this bash command: echo MINIAPP_PERM_CHECK"})
    miniapp_perm_msgs = collect(ws_m, WAIT)
    miniapp_msgs.extend(miniapp_perm_msgs)

    # 如果收到 permission.ask，自动回复
    for m in miniapp_perm_msgs:
        if m.get("type") == "permission.ask":
            perm_id = m.get("permissionId")
            if perm_id:
                print(f"  Replying permission: {perm_id}")
                requests.post(f"{SS_URL}/api/skill/sessions/{miniapp_sid}/permissions/{perm_id}",
                              headers={"Cookie": f"userId={USER_ID}", "Content-Type": "application/json"},
                              json={"response": "once"})
                extra = collect(ws_m, WAIT)
                miniapp_msgs.extend(extra)
                break

    ws_m.close()
    m_grouped = group_by_type(miniapp_msgs)
    print(f"Types: {sorted(m_grouped.keys())}")

    # ============================================
    # Round 2: external path
    # ============================================
    print("\n--- Round 2: External path ---")
    ext_sid = f"cmp-ext-{int(time.time())}"

    ws_e = connect_external()
    print("External WS connected")

    # 预热 session
    print("Warming up external session...")
    requests.post(f"{SS_URL}/api/external/invoke",
                  headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                  json={"action": "chat", "businessDomain": "im", "sessionType": "direct",
                        "sessionId": ext_sid, "assistantAccount": AGENT_AK,
                        "payload": {"content": "Reply OK", "msgType": "text"}})
    warmup = collect(ws_e, WAIT)

    # 发普通消息
    print("Sending chat via external invoke...")
    requests.post(f"{SS_URL}/api/external/invoke",
                  headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                  json={"action": "chat", "businessDomain": "im", "sessionType": "direct",
                        "sessionId": ext_sid, "assistantAccount": AGENT_AK,
                        "payload": {"content": "Reply: EXTERNAL_COMPARE_TEST", "msgType": "text"}})
    external_msgs = collect(ws_e, WAIT)

    # 发触发 permission 的消息
    print("Sending permission-triggering chat via external invoke...")
    requests.post(f"{SS_URL}/api/external/invoke",
                  headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                  json={"action": "chat", "businessDomain": "im", "sessionType": "direct",
                        "sessionId": ext_sid, "assistantAccount": AGENT_AK,
                        "payload": {"content": "Run this bash command: echo EXTERNAL_PERM_CHECK", "msgType": "text"}})
    external_perm_msgs = collect(ws_e, WAIT)
    external_msgs.extend(external_perm_msgs)

    # 如果收到 permission.ask，自动回复
    for m in external_perm_msgs:
        if m.get("type") == "permission.ask":
            perm_id = m.get("permissionId")
            if perm_id:
                print(f"  Replying permission: {perm_id}")
                requests.post(f"{SS_URL}/api/external/invoke",
                              headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                              json={"action": "permission_reply", "businessDomain": "im",
                                    "sessionType": "direct", "sessionId": ext_sid,
                                    "assistantAccount": AGENT_AK,
                                    "payload": {"permissionId": perm_id, "response": "once"}})
                extra = collect(ws_e, WAIT)
                external_msgs.extend(extra)
                break

    ws_e.close()
    e_grouped = group_by_type(external_msgs)
    print(f"Types: {sorted(e_grouped.keys())}")

    # ============================================
    # Compare
    # ============================================
    print("\n" + "=" * 70)
    print("Field Comparison")
    print("=" * 70)

    all_types = sorted(set(list(m_grouped.keys()) + list(e_grouped.keys())))
    passed = 0
    failed = 0
    skipped = 0

    for t in all_types:
        if t in m_grouped and t in e_grouped:
            mk = non_null_keys(m_grouped[t])
            ek = non_null_keys(e_grouped[t])
            if mk == ek:
                print(f"[MATCH] {t}")
                print(f"  fields({len(mk)}): {mk}")
                passed += 1
            else:
                only_m = sorted(set(mk) - set(ek))
                only_e = sorted(set(ek) - set(mk))
                print(f"[DIFF]  {t}")
                print(f"  miniapp({len(mk)}):  {mk}")
                print(f"  external({len(ek)}): {ek}")
                if only_m:
                    print(f"  ONLY miniapp:  {only_m}")
                if only_e:
                    print(f"  ONLY external: {only_e}")
                failed += 1
        elif t in m_grouped:
            print(f"[ONLY_MINIAPP]  {t}: {non_null_keys(m_grouped[t])}")
            skipped += 1
        else:
            print(f"[ONLY_EXTERNAL] {t}: {non_null_keys(e_grouped[t])}")
            skipped += 1
        print()

    # Deep compare text.done
    if "text.done" in m_grouped and "text.done" in e_grouped:
        print("-" * 70)
        print("Deep: text.done field type comparison")
        print("-" * 70)
        mm, em = m_grouped["text.done"], e_grouped["text.done"]
        for k in sorted(set(list(mm.keys()) + list(em.keys()))):
            mv, ev = mm.get(k), em.get(k)
            mt = type(mv).__name__ if mv is not None else "null"
            et = type(ev).__name__ if ev is not None else "null"
            s = "OK" if mt == et else "TYPE_DIFF"
            print(f"  [{s}] {k}: miniapp={mt}, external={et}")
        print()

    print("=" * 70)
    print(f"Result: MATCH={passed} DIFF={failed} ONE_SIDE={skipped} TOTAL={passed+failed+skipped}")
    if failed > 0:
        print("VERDICT: PROTOCOL NOT CONSISTENT")
    elif skipped > 0:
        print(f"VERDICT: CONSISTENT for {passed} shared types, {skipped} types only on one side")
    else:
        print("VERDICT: FULLY CONSISTENT")
    print("=" * 70)

    if failed > 0:
        exit(1)


if __name__ == "__main__":
    main()
