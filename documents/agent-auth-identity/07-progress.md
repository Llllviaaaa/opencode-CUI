# 进度记录

> 状态更新时间：2026-03-09 22:00

## 当前状态

```text
文档状态：已完成
代码状态：已完成
单元测试：已通过（37 tests, 0 failures）
联调状态：进行中（握手成功，子协议协商待验证）
部署状态：未部署
```

## 已完成工作

### Phase 1: 数据模型与数据库迁移 ✅

- `AgentConnection.java` 新增 `macAddress` 字段
- `AgentConnectionRepository.java` 新增 `findByAkIdAndToolType()`、`updateAgentInfo()`
- `AgentConnectionMapper.xml` 新增 `mac_address` 映射 + 2 条新 SQL
- `GatewayMessage.java` 新增 `macAddress`/`reason` 字段 + `registerOk()`/`registerRejected()`
- `V3__agent_identity_persistence.sql` 迁移脚本

### Phase 2: 服务层改造 ✅

- `DeviceBindingService.java`（新建）：第三方设备绑定校验 + fail-open
- `AgentRegistryService.java`：身份持久化（查找复用 / 新建）
- `AgentWebSocketHandler.java`：子协议认证 + 三步注册校验 + 注册超时

### Phase 3: 客户端改造 ✅

- `GatewayConnection.ts`：auth 走子协议 + 4409/4403/4408 不自动重连 + `rejected` 事件
- `plugin.ts`：`getMacAddress()` + macAddress 字段 + `rejected` 事件监听

### Phase 4: 测试 ✅

- `GatewayMessageTest.java`：适配新签名 + 新增 `registerOk/registerRejected` 测试
- Gateway 编译通过
- 全部 37 个单元测试通过

## 已确认决策

- 认证改用 WebSocket 子协议 `Sec-WebSocket-Protocol: auth.{base64url-json}`
- **必须使用 Base64URL 编码**（RFC 4648 §5），不能用标准 Base64（Bun 严格校验）
- **服务端必须回显完整子协议值**（不能简写为 `auth`，Bun 严格匹配）
- 两阶段校验：握手验签 + register 校验设备绑定和重复连接
- 重复连接策略：保留旧连接、拒绝新连接（close code 4409）
- Agent 身份持久化：同 AK + toolType 复用已有记录
- 设备绑定：默认关闭，不可用时降级放行
- 连接清理依赖心跳超时机制（已有）
- 注册超时：10 秒

## 已识别风险

### 1. PC Agent 必须同步更新

Gateway 不再接受 URL Query Parameter 认证。如果 PC Agent 未更新，连接将失败。

**缓解**：部署时必须同步更新 Gateway 和 PC Agent。

### 2. 数据库迁移有数据影响

V3 迁移脚本会删除重复记录并重置状态。

**缓解**：迁移前确认无活跃会话；迁移后所有 Agent 需重新连接。

### 3. 设备绑定服务依赖

如果未来启用设备绑定校验，依赖第三方服务的可用性。

**缓解**：fail-open 策略，服务不可用时降级放行。

## 待实施项

- 数据库 V3 迁移脚本执行
- E2E 联调测试（子协议协商验证）
- 可选：补充 `AgentRegistryServiceTest`、`DeviceBindingServiceTest`
- 可选：补充 `GatewayConnection.test.ts`
- 部署上线

## 建议下一步

1. 重启 Gateway + OpenCode 验证子协议协商修复
2. 在测试环境执行 Flyway V3 迁移
3. 同步部署 Gateway 和 PC Agent
4. 手动验证：正常连接 → 重复连接拒绝 → 重启身份持久化
5. 检查 Nginx/ALB 日志确认 URL 无敏感参数

### Phase 5: 调试联调 + Bun 兼容性修复 ✅

> 日期：2026-03-09 20:00 - 22:00

在本地联调阶段发现 PC Agent 无法连接 Gateway，经排查确认以下问题：

#### 5.1 EventRelay.ts TS2554 编译错误

- **问题**：`EventRelay.ts` 第 580、601 行 `this.gateway.send()` 传入了多余的第二个参数（上下文字符串），导致 TypeScript 编译错误
- **修复**：移除多余参数

#### 5.2 .opencode/node_modules 链接失效

- **问题**：`.opencode/node_modules/@opencode-cui/pc-agent/` 目录为空，bun 的 `file:` 链接失效
- **修复**：在 `.opencode/` 下执行 `npm install` 重建链接

#### 5.3 Base64 编码不兼容 Bun 运行时

- **问题**：标准 Base64 编码中的 `+`、`/`、`=` 字符不是合法的 WebSocket 子协议 token 字符（RFC 6455 §4.1 / RFC 7230 tchar）。Bun 运行时严格校验，抛出 `SyntaxError: Wrong protocol`
- **修复**：
  - 客户端 `GatewayConnection.ts`：`.toString('base64')` → `.toString('base64url')`
  - 服务端 `AgentWebSocketHandler.java`：`Base64.getDecoder()` → `Base64.getUrlDecoder()`

#### 5.4 子协议响应不匹配

- **问题**：服务端 `beforeHandshake` 回复 `Sec-WebSocket-Protocol: auth`，但客户端发送的是 `auth.{base64url}`。RFC 6455 要求精确匹配。Bun 检测到不匹配后立即 `Connection reset`（code=1006）
- **修复**：服务端改为回显完整子协议值 `AUTH_PROTOCOL_PREFIX + authPayload`

