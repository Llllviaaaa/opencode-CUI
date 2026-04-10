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
        "protocol": "sse",
        "authType": "soa"
    },
    "test-personal-ak": {
        "identityType": "2",  # personal
        "hisAppId": None,
        "endpoint": None,
        "protocol": None,
        "authType": None
    }
}

# IM 消息记录
im_messages = []


# ========== 1. 上游助手信息 API ==========

@app.route('/appstore/wecodeapi/open/ak/info', methods=['GET'])
def get_assistant_info():
    ak = request.args.get('ak')
    print(f"[上游API] 查询助手信息: ak={ak}")

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


# ========== 2. 云端 Agent SSE 接口 ==========

@app.route('/api/v1/chat', methods=['POST'])
def cloud_chat():
    body = request.get_json()
    topic_id = body.get('topicId', 'unknown')
    content = body.get('content', '')
    assistant_account = body.get('assistantAccount', '')

    print(f"[云端SSE] 收到请求: topicId={topic_id}, content={content}")

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
                "properties": {"content": chunk, "role": "assistant"}
            }})
            time.sleep(0.05)

        # text.done
        yield sse_event({"type": "tool_event", "toolSessionId": ts, "event": {
            "type": "text.done",
            "properties": {
                "content": reply_text,
                "role": "assistant",
                "messageId": f"msg-{uuid.uuid4().hex[:8]}",
                "partId": f"part-{uuid.uuid4().hex[:8]}"
            }
        }})
        time.sleep(0.1)

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
    """查看所有收到的 IM 消息（测试验证用）"""
    return jsonify(im_messages)


@app.route('/mock/im-messages', methods=['DELETE'])
def clear_im_messages():
    """清空 IM 消息记录"""
    im_messages.clear()
    return jsonify({"message": "cleared"})


@app.route('/mock/health', methods=['GET'])
def health():
    return jsonify({"status": "ok", "services": [
        "上游助手信息 API: GET /appstore/wecodeapi/open/ak/info?ak=xxx",
        "云端 SSE: POST /api/v1/chat",
        "IM 单聊: POST /v1/welinkim/im-service/chat/app-user-chat",
        "IM 群聊: POST /v1/welinkim/im-service/chat/app-group-chat",
        "IM 消息查看: GET /mock/im-messages",
        "IM 消息清空: DELETE /mock/im-messages",
    ]})


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
    print("测试用 AK：")
    print("  业务助手: test-business-ak (identityType=3)")
    print("  个人助手: test-personal-ak (identityType=2)")
    print()
    print("辅助接口：")
    print("  GET    http://localhost:9999/mock/health")
    print("  GET    http://localhost:9999/mock/im-messages")
    print("  DELETE http://localhost:9999/mock/im-messages")
    print()
    print("=" * 60)
    app.run(host='0.0.0.0', port=9999, debug=True)
