# 进度记录

> 状态更新时间：2026-03-09

## 当前状态

```text
文档状态：已完成
代码状态：已完成
单元测试：已通过（37 tests, 0 failures）
联调状态：未开始
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

- 认证改用 WebSocket 子协议 `Sec-WebSocket-Protocol: auth.{base64-json}`
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
- 手动 E2E 联调测试
- 可选：补充 `AgentRegistryServiceTest`、`DeviceBindingServiceTest`
- 可选：补充 `GatewayConnection.test.ts`
- 部署上线

## 建议下一步

1. 在测试环境执行 Flyway V3 迁移
2. 同步部署 Gateway 和 PC Agent
3. 手动验证：正常连接 → 重复连接拒绝 → 重启身份持久化
4. 检查 Nginx/ALB 日志确认 URL 无敏感参数
