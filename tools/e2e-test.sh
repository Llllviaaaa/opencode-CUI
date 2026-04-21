#!/bin/bash
# 云端 Agent 对接 端到端测试脚本
# 前提：mock 服务已启动 (python tools/mock-cloud-server.py)
# 前提：skill-server 和 ai-gateway 已启动

MOCK_URL="http://localhost:9999"
SS_URL="http://localhost:8080"
GW_URL="http://localhost:8081"

echo "=========================================="
echo "云端 Agent 对接 端到端测试"
echo "=========================================="

# 0. 检查 mock 服务
echo ""
echo "[Test 0] 检查 Mock 服务..."
HEALTH=$(curl -s $MOCK_URL/mock/health)
if [ $? -ne 0 ]; then
    echo "  ❌ Mock 服务未启动，请先运行: python tools/mock-cloud-server.py"
    exit 1
fi
echo "  ✅ Mock 服务正常"

# 1. 测试上游 API mock
echo ""
echo "[Test 1] 上游助手信息 API..."
RESP=$(curl -s "$MOCK_URL/appstore/wecodeapi/open/ak/info?ak=test-business-ak")
echo "  响应: $RESP"
IDENTITY=$(echo $RESP | python -c "import sys,json; print(json.load(sys.stdin)['data']['identityType'])" 2>/dev/null)
if [ "$IDENTITY" = "3" ]; then
    echo "  ✅ 业务助手(identityType=3)返回正确"
else
    echo "  ❌ 期望 identityType=3, 实际: $IDENTITY"
fi

RESP2=$(curl -s "$MOCK_URL/appstore/wecodeapi/open/ak/info?ak=test-personal-ak")
IDENTITY2=$(echo $RESP2 | python -c "import sys,json; print(json.load(sys.stdin)['data']['identityType'])" 2>/dev/null)
if [ "$IDENTITY2" = "2" ]; then
    echo "  ✅ 个人助手(identityType=2)返回正确"
else
    echo "  ❌ 期望 identityType=2, 实际: $IDENTITY2"
fi

# 2. 测试云端 SSE mock
echo ""
echo "[Test 2] 云端 SSE 接口..."
SSE_RESP=$(curl -s -N --max-time 10 -X POST "$MOCK_URL/api/v1/chat" \
    -H "Content-Type: application/json" \
    -d '{"topicId":"test-topic-001","content":"测试消息","assistantAccount":"test-bot"}' 2>&1)

# 检查是否包含各种事件类型
HAS_PLANNING=$(echo "$SSE_RESP" | grep -c "planning.delta")
HAS_SEARCHING=$(echo "$SSE_RESP" | grep -c "searching")
HAS_SEARCH_RESULT=$(echo "$SSE_RESP" | grep -c "search_result")
HAS_REFERENCE=$(echo "$SSE_RESP" | grep -c "reference")
HAS_THINKING=$(echo "$SSE_RESP" | grep -c "thinking.delta")
HAS_TEXT=$(echo "$SSE_RESP" | grep -c "text.delta")
HAS_TEXT_DONE=$(echo "$SSE_RESP" | grep -c "text.done")
HAS_ASK_MORE=$(echo "$SSE_RESP" | grep -c "ask_more")
HAS_TOOL_DONE=$(echo "$SSE_RESP" | grep -c "tool_done")

echo "  事件类型检查:"
[ $HAS_PLANNING -gt 0 ] && echo "    ✅ planning.delta" || echo "    ❌ planning.delta 缺失"
[ $HAS_SEARCHING -gt 0 ] && echo "    ✅ searching" || echo "    ❌ searching 缺失"
[ $HAS_SEARCH_RESULT -gt 0 ] && echo "    ✅ search_result" || echo "    ❌ search_result 缺失"
[ $HAS_REFERENCE -gt 0 ] && echo "    ✅ reference" || echo "    ❌ reference 缺失"
[ $HAS_THINKING -gt 0 ] && echo "    ✅ thinking.delta" || echo "    ❌ thinking.delta 缺失"
[ $HAS_TEXT -gt 0 ] && echo "    ✅ text.delta" || echo "    ❌ text.delta 缺失"
[ $HAS_TEXT_DONE -gt 0 ] && echo "    ✅ text.done" || echo "    ❌ text.done 缺失"
[ $HAS_ASK_MORE -gt 0 ] && echo "    ✅ ask_more" || echo "    ❌ ask_more 缺失"
[ $HAS_TOOL_DONE -gt 0 ] && echo "    ✅ tool_done" || echo "    ❌ tool_done 缺失"

# 3. 测试 IM 推送 mock
echo ""
echo "[Test 3] IM 推送接口（直接测 mock）..."
curl -s -X DELETE "$MOCK_URL/mock/im-messages" > /dev/null

curl -s -X POST "$MOCK_URL/v1/welinkim/im-service/chat/app-user-chat" \
    -H "Content-Type: application/json" \
    -d '{"senderAccount":"test-bot","sessionId":"im-123","content":"推送测试","contentType":13}' > /dev/null

IM_COUNT=$(curl -s "$MOCK_URL/mock/im-messages" | python -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null)
if [ "$IM_COUNT" = "1" ]; then
    echo "  ✅ IM 出站消息记录成功"
else
    echo "  ❌ 期望 1 条消息, 实际: $IM_COUNT"
fi

# 4. 测试 GW IM 推送接口（需要 GW 服务运行）
echo ""
echo "[Test 4] GW IM 推送接口（需要 GW 服务）..."
GW_PUSH_RESP=$(curl -s -w "%{http_code}" -X POST "$GW_URL/api/gateway/cloud/im-push" \
    -H "Content-Type: application/json" \
    -d '{"assistantAccount":"test-bot","userAccount":"c30051824","imGroupId":null,"topicId":"cloud-test-001","content":"GW推送测试"}' 2>/dev/null)
GW_STATUS="${GW_PUSH_RESP: -3}"
if [ "$GW_STATUS" = "200" ]; then
    echo "  ✅ GW 推送接口返回 200"
elif [ "$GW_STATUS" = "000" ]; then
    echo "  ⏭️  GW 未启动，跳过"
else
    echo "  ❌ GW 推送接口返回 $GW_STATUS"
fi

# 5. 管理接口测试
echo ""
echo "[Test 5] SysConfig 管理接口（需要 SS 服务）..."
CONFIG_RESP=$(curl -s -w "%{http_code}" -X POST "$SS_URL/api/admin/configs" \
    -H "Content-Type: application/json" \
    -d '{"configType":"cloud_request_strategy","configKey":"app_test_001","configValue":"default","description":"测试助手","status":1,"sortOrder":0}' 2>/dev/null)
CONFIG_STATUS="${CONFIG_RESP: -3}"
if [ "$CONFIG_STATUS" = "200" ]; then
    echo "  ✅ SysConfig 创建成功"
elif [ "$CONFIG_STATUS" = "000" ]; then
    echo "  ⏭️  SS 未启动，跳过"
else
    echo "  ❌ SysConfig 创建返回 $CONFIG_STATUS"
fi

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
echo ""
echo "如需完整端到端测试，请确保以下服务已启动："
echo "  1. python tools/mock-cloud-server.py (端口 9999)"
echo "  2. skill-server (端口 8080)"
echo "  3. ai-gateway (端口 8081)"
echo ""
echo "配置要求（application.yml）："
echo "  SS:"
echo "    skill.assistant-info.api-url: http://localhost:9999/appstore/wecodeapi/open/ak/info"
echo "    skill.im.api-url: http://localhost:9999"
echo "  GW:"
echo "    gateway.cloud-route.api-url: http://localhost:9999/appstore/wecodeapi/open/ak/info"
