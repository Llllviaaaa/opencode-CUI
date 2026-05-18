# 默认助手规则运维 SOP

> 任务 05-15-noauth-conversation-permission 配套运维文档。

## 强约束（第一页必读）

`profileName == businessTag` 必须**同名**（codex N6 SOP 路标）：

```
规则表 businessTag → 同名 profileName → 同名 strategy/decoder
规则表 businessTag → GW cloud_route_fallback_v2:{同名}:{scope}
```

例如 businessTag = "assistant_square"：
- SS `cloud_protocol_profile:assistant_square` 配 profile 定义
- GW `cloud_route_fallback_v2:assistant_square:chat` / `...:question` / `...:permission` 配兜底 endpoint

**不遵守此约定会导致 SS profile 选择和 GW fallback 配置错位，排障极困难**。

## 规则模板

```
config_type   = "default_assistant_rule"
config_key    = "{businessSessionDomain}:{businessSessionType}"   ← 如 "helpdesk:direct"
config_value  = JSON {"ak":"AK_V","assistantAccount":"ACC_V","businessTag":"assistant_square"}
status        = 1
```

3 字段必须**非空**：
- `ak`: 虚拟 ak（建议 `DEFAULT_*` 前缀防与真业务 ak 撞车，但本任务不强制——Known Issues #4）
- `assistantAccount`: 虚拟 assistantAccount
- `businessTag`: SS 内部喂给 `CloudRequestProfileRegistry.resolve(...)` 选 CloudRequestProfile；wire 上以 `payload.cloudProfile` 字段传给 GW

## 增删启停操作

### 新增规则

```bash
# 用现有 admin endpoint（路径 /api/admin/configs/**）
curl -X POST https://<host>/api/admin/configs \
  -H "Authorization: Bearer <existing-admin-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "configType": "default_assistant_rule",
    "configKey": "helpdesk:direct",
    "configValue": "{\"ak\":\"DEFAULT_AK_HELPDESK\",\"assistantAccount\":\"DEFAULT_ACC_HELPDESK\",\"businessTag\":\"assistant_square\"}",
    "status": 1
  }'
```

`SysConfigService.create` 自动清空对应 Redis cache。下次 lookup 拿新值。

### 修改规则

```bash
curl -X PUT https://<host>/api/admin/configs/{id} \
  -H "Authorization: Bearer <existing-admin-token>" \
  -H "Content-Type: application/json" \
  -d '{"configValue": "{\"ak\":...,...}"}'
```

`SysConfigService.update` 自动 evict Redis 缓存，下次 lookup 拿新值。

### 禁用规则

设 `status=0` —— `SysConfigService.getValue` 不返已禁用配置 → lookup 返 empty → 后续 createSession 走 400 分支。

### 删除规则

```bash
curl -X DELETE https://<host>/api/admin/configs/{id}
```

## 与 GW cloud_route_fallback_v2 的对应关系

每个 businessTag 必须在 GW 配齐 3 个 scope 的 fallback（chat / question / permission）：

```
type=cloud_route_fallback_v2  key="assistant_square:chat"        value={"channelAddress":"<SSE URL>","channelType":"sse","authType":"soa"}
type=cloud_route_fallback_v2  key="assistant_square:question"    value={"channelAddress":"<webhook URL>","channelType":"webhook","authType":"soa"}
type=cloud_route_fallback_v2  key="assistant_square:permission"  value={"channelAddress":"<webhook URL>","channelType":"webhook","authType":"soa"}
```

少配一个 → 对应 scope 报 `callback_config_missing` TOOL_ERROR。

## 部署后强制验证 checklist（N6 补丁）

新增 / 修改 `default_assistant_rule` 后，运维**必须在 5 分钟内**跑完三个 smoke test：

### 步骤 1：建会话

```bash
curl -X POST https://<host>/api/skill/sessions \
  -H "Cookie: userId=<test-user-cookie>" \
  -H "Content-Type: application/json" \
  -d '{
    "businessSessionDomain": "helpdesk",
    "businessSessionType": "direct",
    "businessSessionId": "smoke-test-{timestamp}",
    "title": "smoke test"
  }'
```

**预期**：HTTP 200，响应里 `ak=DEFAULT_AK_HELPDESK` / `assistantAccount=DEFAULT_ACC_HELPDESK` / `toolSessionId=<snowflake>` / `status=ACTIVE`。

### 步骤 2：发一条 chat

```bash
curl -X POST https://<host>/api/skill/sessions/{sessionId}/messages \
  -H "Cookie: userId=<test-user-cookie>" \
  -H "Content-Type: application/json" \
  -d '{"content": "hello"}'
```

**预期**：SSE 回流 `text.delta` / `text.done`，**不报** `callback_config_missing`。

### 步骤 3：发一条 question_reply

```bash
curl -X POST https://<host>/api/skill/sessions/{sessionId}/messages \
  -H "Cookie: userId=<test-user-cookie>" \
  -H "Content-Type: application/json" \
  -d '{"content": "answer", "toolCallId": "<from-step-2-response>"}'
```

**预期**：HTTP 200，webhook 立刻返 200，**不报** `callback_config_missing`。

**任一步失败 → 回滚配置 + 排查**：
- 失败原因大概率是 SS `cloud_protocol_profile:{businessTag}` 缺、或 GW `cloud_route_fallback_v2:{businessTag}:{scope}` 缺
- 排障时先看 SS 日志 + GW 日志的 `callback_config_missing` 关键字
