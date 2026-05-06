"""
云端 Agent 对接 Mock 服务器
用于端到端集成测试，模拟以下外部接口：
1. 上游助手信息 API (GET /appstore/wecodeapi/open/ak/info)
2. 云端 Agent SSE 接口 (POST /api/v1/chat)
3. IM 出站 API (POST /v1/welinkim/im-service/chat/*)

启动: python tools/mock-cloud-server.py
默认端口: 9999
"""

import json
import time
import uuid
from flask import Flask, request, Response, jsonify

app = Flask(__name__)

# ========== 配置 ==========

# 助手信息映射：ak → assistant info
ASSISTANT_INFO = {
    "test-business-ak": {
        "identityType": "3",  # business
        "hisAppId": "app_test_001",
        "endpoint": "http://localhost:9999/api/v1/chat",
        "protocol": "2",     # 1=rest, 2=sse, 3=websocket
        "authType": "1"      # 1=soa
    },
    "test-personal-ak": {
        "identityType": "2",  # personal
        "hisAppId": None,
        "endpoint": None,
        "protocol": None,
        "authType": None
    },
    "test-ak-001": {
        "identityType": "2",  # personal (real local OpenCode agent)
        "hisAppId": None,
        "endpoint": None,
        "protocol": None,
        "authType": None
    }
}

# IM 消息记录
im_messages = []

# SSE 请求记录
sse_requests = []

# WebHook 请求记录（q_r / p_r）
webhook_requests = []

# E2E 保活验证用：chat SSE 流推完 question 事件后 await event；q_r webhook 收到后 .set() 让 SSE 流继续
import threading
_chat_continue_events = {}  # topicId -> threading.Event

# 开关控制（用于模拟故障）
switches = {
    "upstream_api_enabled": True,   # 上游 API 是否可用
    "sse_enabled": True,            # 云端 SSE 是否可用
    "sse_return_429": False,        # SSE 是否返回 429
}

# GW v2 CallbackConfigService 用：(ak, scope) → 回调配置
# channelType: 1=webhook, 2=sse, 3=websocket
# authType: 0=none, 1=soa, 2=apig
CALLBACK_CONFIG = {
    ("test-business-ak", "callback:weagent:chat"): {
        "channelType": 2,
        "channelAddress": "http://localhost:9999/api/v1/chat",
        "authType": 0,
    },
    ("test-business-ak", "callback:weagent:question_reply"): {
        "channelType": 1,
        "channelAddress": "http://localhost:9999/api/v1/question_reply",
        "authType": 0,
    },
    ("test-business-ak", "callback:weagent:permission_reply"): {
        "channelType": 1,
        "channelAddress": "http://localhost:9999/api/v1/permission_reply",
        "authType": 0,
    },
}


# ========== 1. 上游助手信息 API ==========

@app.route('/appstore/wecodeapi/open/ak/info', methods=['GET', 'POST'])
def get_assistant_info():
    # 支持：query param ?ak=xxx 或 body {"ak":"xxx"}（GET+body 或 POST+body）
    ak = request.args.get('ak')
    if not ak and request.data:
        try:
            body = json.loads(request.data.decode('utf-8'))
            ak = body.get('ak')
        except Exception:
            pass
    print(f"[上游API] 查询助手信息: ak={ak}")

    if not switches["upstream_api_enabled"]:
        print(f"[上游API] 已禁用，返回 503")
        return jsonify({"error": "service unavailable"}), 503

    info = ASSISTANT_INFO.get(ak)
    if info:
        return jsonify({
            "code": "200",
            "messageZh": "成功！",
            "messageEn": "success!",
            "data": info
        })
    else:
        return jsonify({
            "code": "404",
            "messageZh": "未找到助手",
            "messageEn": "assistant not found"
        }), 200  # 上游接口业务错误也返回 200


# ========== 1b. 助手账号解析 API ==========

# assistantAccount → {ak, create_by} 映射
ASSISTANT_ACCOUNT_MAP = {
    "test-business-ak": {"ak": "test-business-ak", "ownerWelinkId": "900001", "create_by": "900001"},
    "test-personal-ak": {"ak": "test-personal-ak", "ownerWelinkId": "900002", "create_by": "900002"},
    "test-ak-001": {"ak": "test-ak-001", "ownerWelinkId": "1", "create_by": "1"},
    # 业务助手账号 → 业务助手 ak
    "test-business-bot": {"ak": "test-business-ak", "ownerWelinkId": "900001", "create_by": "900001"},
    "e2e-cb-bot": {"ak": "test-business-ak", "ownerWelinkId": "900001", "create_by": "900001"},
}

@app.route('/assistant-api/integration/v4-1/we-crew/instance/query', methods=['GET'])
def resolve_assistant_account():
    account = request.args.get('partnerAccount')
    print(f"[助手解析] 查询: partnerAccount={account}")

    info = ASSISTANT_ACCOUNT_MAP.get(account)
    if info:
        # SS AssistantAccountResolverService.judge 要求 body.code == 200 + data.appKey + data.ownerWelinkId
        return jsonify({
            "code": 200,
            "data": {
                "appKey": info["ak"],
                "ownerWelinkId": info["ownerWelinkId"],
                "create_by": info["create_by"]
            }
        })
    else:
        return jsonify({"code": 200, "data": {}}), 200


# ========== 2. 云端 Agent SSE 接口 ==========

@app.route('/api/v1/chat', methods=['POST'])
def cloud_chat():
    body = request.get_json()
    topic_id = body.get('topicId', 'unknown')
    content = body.get('content', '')
    assistant_account = body.get('assistantAccount', '')

    print(f"[云端SSE] 收到请求: topicId={topic_id}, content={content}")

    # 记录请求
    sse_requests.append({
        "topicId": topic_id,
        "content": content,
        "assistantAccount": assistant_account,
        "body": body,
        "time": time.time()
    })

    if not switches["sse_enabled"]:
        print(f"[云端SSE] 已禁用，返回 503")
        return jsonify({"error": "service unavailable"}), 503

    if switches["sse_return_429"]:
        print(f"[云端SSE] 返回 429 限流")
        return jsonify({"error": "rate limited"}), 429

    def generate_sse():
        ts = topic_id

        # planning 阶段
        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "planning.delta",
            "properties": {"content": "分析用户问题，"}
        }})
        time.sleep(0.1)

        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "planning.delta",
            "properties": {"content": "准备搜索相关资料"}
        }})
        time.sleep(0.1)

        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "planning.done",
            "properties": {"content": "分析用户问题，准备搜索相关资料"}
        }})
        time.sleep(0.1)

        # searching 阶段
        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "searching",
            "properties": {"keywords": ["测试关键词1", "测试关键词2"]}
        }})
        time.sleep(0.1)

        # search_result
        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "search_result",
            "properties": {"results": [
                {"index": "1", "title": "测试文章1", "source": "测试来源"},
                {"index": "2", "title": "测试文章2", "source": "测试来源2"}
            ]}
        }})
        time.sleep(0.1)

        # reference
        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "reference",
            "properties": {"references": [
                {"index": "1", "title": "测试文章1", "url": "https://example.com/1", "source": "测试来源", "content": "这是文章1的内容摘要"},
                {"index": "2", "title": "测试文章2", "url": "https://example.com/2", "source": "测试来源2", "content": "这是文章2的内容摘要"}
            ]}
        }})
        time.sleep(0.1)

        # thinking 阶段
        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "thinking.delta",
            "properties": {"content": "让我整理一下", "role": "assistant"}
        }})
        time.sleep(0.1)

        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "thinking.done",
            "properties": {"content": "让我整理一下搜索结果来回答", "role": "assistant"}
        }})
        time.sleep(0.1)

        # text 阶段（流式）
        reply_text = f"您好！这是对「{content}」的回复。\n\n根据搜索结果[1][2]，以下是详细信息..."
        for i in range(0, len(reply_text), 3):
            chunk = reply_text[i:i+3]
            yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
                "type": "text.delta",
                "content": chunk, "role": "assistant",
                "properties": {"content": chunk, "role": "assistant"}
            }})
            time.sleep(0.05)

        # text.done
        msg_id = f"msg-{uuid.uuid4().hex[:8]}"
        part_id = f"part-{uuid.uuid4().hex[:8]}"
        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "text.done",
            "content": reply_text, "role": "assistant",
            "messageId": msg_id, "partId": part_id,
            "properties": {
                "content": reply_text,
                "role": "assistant",
                "messageId": msg_id,
                "partId": part_id
            }
        }})
        time.sleep(0.1)

        # E2E 保活验证：content 含 "KEEPALIVE" → 发 question 事件并 wait q_r webhook，
        # 收到 webhook 后再继续推后续 text.delta + tool_done 验证 chat SSE 长连接保活
        if "KEEPALIVE" in content:
            print(f"[云端SSE] 进入保活分支：发 question 事件并等待 q_r webhook ts={ts}")
            yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
                "type": "question",
                "properties": {
                    "toolCallId": "call-keepalive-001",
                    "messageId": f"msg-q-{uuid.uuid4().hex[:6]}",
                    "partId": f"part-q-{uuid.uuid4().hex[:6]}",
                    "header": "请选择",
                    "question": "继续吗？",
                    "options": ["yes", "no"]
                }
            }})
            # 等 q_r webhook 触发（最长 10s）
            ev = _chat_continue_events.setdefault(ts, threading.Event())
            got = ev.wait(timeout=10)
            print(f"[云端SSE] 保活 wait 结束 ts={ts}, got_qr={got}")
            # 收到 q_r 后继续推后续 text.delta（验证 lifecycle resume + 客户端继续收事件）
            after_text = "收到您的回答，继续给您后续内容。"
            for i in range(0, len(after_text), 4):
                chunk = after_text[i:i+4]
                yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
                    "type": "text.delta",
                    "content": chunk, "role": "assistant",
                    "properties": {"content": chunk, "role": "assistant"}
                }})
                time.sleep(0.05)
            _chat_continue_events.pop(ts, None)

        # ask_more
        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "ask_more",
            "properties": {"questions": ["还有什么想了解的？", "需要更详细的说明吗？"]}
        }})
        time.sleep(0.1)

        # tool_done
        yield sse_event({"type": "tool_done", "toolSessionId": ts, "usage": {
            "input_tokens": 150, "output_tokens": len(reply_text)
        }})

        print(f"[云端SSE] 响应完成: topicId={topic_id}")

    return Response(
        generate_sse(),
        mimetype='text/event-stream',
        headers={
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'X-Accel-Buffering': 'no'
        }
    )


# ========== 2.6. GW v2 Callback Config - (ak, scope) → channelType/channelAddress/authType ==========

@app.route('/gateway/callbacks/config', methods=['POST'])
def gateway_callback_config():
    body = request.get_json() or {}
    ak = body.get('ak')
    scope = body.get('scope')
    print(f"[GW v2 callback-config] ak={ak}, scope={scope}")
    cfg = CALLBACK_CONFIG.get((ak, scope))
    if cfg:
        return jsonify({"code": "200", "messageZh": "操作成功", "messageEn": "Success", "data": {
            "ak": ak,
            "scope": scope,
            "channelType": cfg["channelType"],
            "channelAddress": cfg["channelAddress"],
            "authType": cfg["authType"],
        }})
    # 未订阅 → data: null
    return jsonify({"code": "200", "messageZh": "操作成功", "messageEn": "Success", "data": None})


# ========== 2.7. 云端 q_r / p_r WebHook 接收端 ==========

@app.route('/api/v1/question_reply', methods=['POST'])
def cloud_question_reply():
    body = request.get_json()
    topic_id = body.get('topicId')
    print(f"[云端 q_r WebHook] topicId={topic_id}, "
          f"toolCallId={body.get('replyContext', {}).get('toolCallId')}, "
          f"answers={body.get('replyContext', {}).get('answers')}")
    webhook_requests.append({"type": "question_reply", "body": body, "time": time.time()})
    # 触发对应 chat SSE 流继续推后续事件（保活验证）
    ev = _chat_continue_events.get(topic_id)
    if ev is not None:
        ev.set()
        print(f"[云端 q_r WebHook] resumed chat SSE for topicId={topic_id}")
    return jsonify({"code": "200", "message": "ok"})


@app.route('/api/v1/permission_reply', methods=['POST'])
def cloud_permission_reply():
    body = request.get_json()
    print(f"[云端 p_r WebHook] permissionId={body.get('replyContext', {}).get('permissionId')}, "
          f"response={body.get('replyContext', {}).get('response')}")
    webhook_requests.append({"type": "permission_reply", "body": body, "time": time.time()})
    return jsonify({"code": "200", "message": "ok"})


# ========== 3. IM 出站 API ==========

@app.route('/v1/welinkim/im-service/chat/app-user-chat', methods=['POST'])
def im_user_chat():
    body = request.get_json()
    print(f"[IM出站-单聊] 收到消息: sender={body.get('senderAccount')}, "
          f"session={body.get('sessionId')}, content={body.get('content', '')[:50]}...")
    im_messages.append({"type": "direct", "body": body, "time": time.time()})
    return jsonify({"code": 200, "message": "success"})


@app.route('/v1/welinkim/im-service/chat/app-group-chat', methods=['POST'])
def im_group_chat():
    body = request.get_json()
    print(f"[IM出站-群聊] 收到消息: sender={body.get('senderAccount')}, "
          f"session={body.get('sessionId')}, content={body.get('content', '')[:50]}...")
    im_messages.append({"type": "group", "body": body, "time": time.time()})
    return jsonify({"code": 200, "message": "success"})


# ========== 辅助接口 ==========

@app.route('/mock/im-messages', methods=['GET'])
def get_im_messages():
    """查看所有收到的 IM 消息"""
    return jsonify(im_messages)


@app.route('/mock/im-messages', methods=['DELETE'])
def clear_im_messages():
    """清空 IM 消息记录"""
    im_messages.clear()
    return jsonify({"message": "cleared"})


@app.route('/mock/sse-requests', methods=['GET'])
def get_sse_requests():
    """查看所有收到的 SSE 请求"""
    return jsonify(sse_requests)


@app.route('/mock/sse-requests', methods=['DELETE'])
def clear_sse_requests():
    """清空 SSE 请求记录"""
    sse_requests.clear()
    return jsonify({"message": "cleared"})


@app.route('/mock/webhook-requests', methods=['GET'])
def get_webhook_requests():
    """查看所有收到的 WebHook 请求（q_r/p_r）"""
    return jsonify(webhook_requests)


@app.route('/mock/webhook-requests', methods=['DELETE'])
def clear_webhook_requests():
    """清空 WebHook 请求记录"""
    webhook_requests.clear()
    return jsonify({"message": "cleared"})


@app.route('/mock/switches', methods=['GET'])
def get_switches():
    """查看开关状态"""
    return jsonify(switches)


@app.route('/mock/switches', methods=['PUT'])
def set_switches():
    """设置开关（用于模拟故障）"""
    body = request.get_json()
    for k, v in body.items():
        if k in switches:
            switches[k] = v
            print(f"[开关] {k} = {v}")
    return jsonify(switches)


@app.route('/mock/switches', methods=['DELETE'])
def reset_switches():
    """重置所有开关为默认值"""
    switches["upstream_api_enabled"] = True
    switches["sse_enabled"] = True
    switches["sse_return_429"] = False
    print("[开关] 全部重置")
    return jsonify(switches)


@app.route('/mock/health', methods=['GET'])
def health():
    return jsonify({"status": "ok", "switches": switches})


# ========== SSE 辅助函数 ==========

def sse_event(data):
    return f"data: {json.dumps(data, ensure_ascii=False)}\n\n"


# ========== 启动 ==========

if __name__ == '__main__':
    print("=" * 60)
    print("云端 Agent 对接 Mock 服务器")
    print("=" * 60)
    print()
    print("Mock 接口列表：")
    print("  上游 API:  GET  http://localhost:9999/appstore/wecodeapi/open/ak/info?ak=test-business-ak")
    print("  云端 SSE:  POST http://localhost:9999/api/v1/chat")
    print("  IM 单聊:   POST http://localhost:9999/v1/welinkim/im-service/chat/app-user-chat")
    print("  IM 群聊:   POST http://localhost:9999/v1/welinkim/im-service/chat/app-group-chat")
    print()
    print("控制接口：")
    print("  GET    /mock/switches        查看开关")
    print("  PUT    /mock/switches        设置开关 (upstream_api_enabled, sse_enabled, sse_return_429)")
    print("  DELETE /mock/switches        重置开关")
    print("  GET    /mock/sse-requests    查看 SSE 请求记录")
    print("  DELETE /mock/sse-requests    清空 SSE 请求记录")
    print("  GET    /mock/im-messages     查看 IM 消息")
    print("  DELETE /mock/im-messages     清空 IM 消息")
    print()
    print("=" * 60)
    app.run(host='0.0.0.0', port=9999, debug=True)
