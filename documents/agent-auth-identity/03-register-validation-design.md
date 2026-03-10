# 注册阶段校验设计

> 版本：1.0  
> 日期：2026-03-09

## 1. 校验时机设计

采用两阶段校验策略：

| 阶段     | 校验内容            | 时机                | 失败行为                    |
| -------- | ------------------- | ------------------- | --------------------------- |
| 握手阶段 | AK/SK 签名校验      | `beforeHandshake()` | 握手拒绝（HTTP 403）        |
| 注册阶段 | 设备绑定 + 重复连接 | `handleRegister()`  | `register_rejected` + close |

### 1.1 为什么不一次性校验

签名校验与设备绑定的关注点不同：

- **签名校验**：验证"你是谁"（身份认证），适合在握手阶段完成
- **设备绑定**：验证"你从哪来"（授权），需要 register 消息中的 macAddress
- **重复连接**：验证"是否允许"（策略），需要在消息通道内完成

## 2. Register 三步校验流程

```text
handleRegister():
  ┌─────────────────────────────────────────────────────┐
  │ Step 1: DeviceBindingService.validate(ak, mac, type)│
  │   → 失败 → register_rejected("device_binding_failed")
  │            + close(4403)                            │
  ├─────────────────────────────────────────────────────┤
  │ Step 2: EventRelayService.hasAgentSession(ak)       │
  │   → true → register_rejected("duplicate_connection")│
  │            + close(4409)                            │
  ├─────────────────────────────────────────────────────┤
  │ Step 3: AgentRegistryService.register()             │
  │   → 查找已有记录 → UPDATE / INSERT                  │
  │   → 注册 WS session                                │
  │   → 通知 SkillServer agent_online                   │
  │   → register_ok                                    │
  └─────────────────────────────────────────────────────┘
```

## 3. 设备绑定校验（Step 1）

### 3.1 服务设计

`DeviceBindingService` 调用第三方服务验证 AK 的设备绑定：

```text
POST {gateway.device-binding.url}
Content-Type: application/json

{
  "ak": "test-ak-001",
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "toolType": "OPENCODE"
}

Response:
{
  "valid": true,
  "message": "ok"
}
```

### 3.2 超时与降级

| 条件                    | 行为                        |
| ----------------------- | --------------------------- |
| 服务返回 `valid: true`  | 通过                        |
| 服务返回 `valid: false` | 拒绝（`register_rejected`） |
| 服务超时（>3s）         | **降级放行** + 告警日志     |
| 服务不可用              | **降级放行** + 告警日志     |
| 功能未启用              | 直接通过                    |

### 3.3 配置

```yaml
gateway:
  device-binding:
    enabled: false          # 默认关闭
    url: http://...         # 第三方服务地址
    timeout-ms: 3000        # 超时时间
```

## 4. 重复连接检测（Step 2）

### 4.1 策略：保留旧连接、拒绝新连接

| 方案                 | 优点                 | 缺点                     |
| -------------------- | -------------------- | ------------------------ |
| 踢旧替新（当前）     | 新连接总能成功       | 正在进行的会话被中断     |
| **保旧拒新（选定）** | **保护进行中的会话** | 需要先关闭旧连接才能启新 |

选择"保旧拒新"的理由：

- 正在进行中的 AI 会话不应被意外中断
- 旧连接断开后（心跳超时、正常关闭），新连接自然可以建立
- 恶意/误操作的新连接不会影响已有工作

### 4.2 检测方式

```java
if (eventRelayService.hasAgentSession(akId)) {
    // 已有活跃 WebSocket session → 拒绝
    sendAndClose(session,
        GatewayMessage.registerRejected("duplicate_connection"),
        new CloseStatus(4409, "duplicate_connection"));
}
```

## 5. 注册超时（辅助机制）

```text
afterConnectionEstablished():
  → 启动 10s 定时器
  → 超时后检查：如果仍未注册 → close(4408, "register_timeout")
```

防止只做握手不发 register 的连接长期占用资源。

## 6. 客户端处理

### 6.1 PC Agent 对拒绝码的处理

```typescript
// 自定义关闭码集合：收到这些码时不自动重连
private static readonly REJECTION_CODES = new Set([
  4403, // device_binding_failed
  4408, // register_timeout
  4409, // duplicate_connection
]);

ws.on('close', (code, reason) => {
  if (REJECTION_CODES.has(code)) {
    this.intentionallyClosed = true;
    this.emit('rejected', { code, reason });
    return; // 不进入 scheduleReconnect()
  }
  // ...正常重连逻辑
});
```

### 6.2 用户提示

```typescript
gateway.on('rejected', ({ code, reason }) => {
  console.error(`Connection rejected: code=${code} reason=${reason}`);
  console.error('Another instance may already be connected with this AK.');
});
```
