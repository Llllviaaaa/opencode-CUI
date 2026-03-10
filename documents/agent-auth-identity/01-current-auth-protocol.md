# 当前认证协议现状

> 版本：1.0  
> 日期：2026-03-09

## 1. 当前实现概览

当前 PCAgent 连接 Gateway 的认证方式是通过 **URL Query Parameter** 传递敏感信息：

```text
ws://gateway:8081/ws/agent?ak=xxx&ts=xxx&nonce=xxx&sign=xxx
```

Gateway 在 WebSocket 握手拦截器 `beforeHandshake()` 中提取这些参数，调用 `AkSkAuthService.verify()` 进行签名校验。

## 2. 当前关键实现

### 2.1 ai-gateway

| 文件                         | 当前职责                                                     |
| ---------------------------- | ------------------------------------------------------------ |
| `AgentWebSocketHandler.java` | 握手拦截 + 从 URL 取 ak/ts/nonce/sign + 消息路由             |
| `AkSkAuthService.java`       | AK/SK 签名校验：查 Redis/DB → HMAC-SHA256 → 时间窗口 + nonce |
| `AgentRegistryService.java`  | Agent 注册/下线/心跳管理                                     |
| `EventRelayService.java`     | 双向中继中枢，管理 WebSocket session 映射                    |

### 2.2 pc-agent

| 文件                   | 当前职责                                               |
| ---------------------- | ------------------------------------------------------ |
| `GatewayConnection.ts` | 维护 WebSocket 连接、`buildUrl()` 拼接 auth 参数到 URL |
| `AkSkAuth.ts`          | 客户端签名：HMAC-SHA256 签名生成                       |
| `plugin.ts`            | 插件入口，发送 `register` 消息                         |

## 3. 当前认证流程

```text
PC Agent                          Gateway
   │                                │
   │ ws://...?ak=X&ts=T&nonce=N&sign=S
   │ ──────────────────────────────→ │
   │                                │ beforeHandshake()
   │                                │   getParameter("ak") → ak
   │                                │   getParameter("ts") → ts
   │                                │   getParameter("nonce") → nonce
   │                                │   getParameter("sign") → sign
   │                                │   AkSkAuthService.verify(ak,ts,nonce,sign)
   │                                │     → 通过 → attributes.put(userId, akId)
   │                                │     → 失败 → return false (握手拒绝)
   │     ← 101 Switching Protocols  │
   │                                │
   │ { type: "register",            │ handleRegister()
   │   deviceName, os,              │   AgentRegistryService.register()
   │   toolType, toolVersion }      │     → 踢旧连接 + INSERT 新记录
   │ ──────────────────────────────→ │   eventRelayService.registerAgentSession()
   │                                │   → 通知 Skill Server agent_online
```

## 4. 当前 Agent 注册逻辑

```java
// AgentRegistryService.register() 当前实现
public AgentConnection register(Long userId, String akId, ...) {
    // 1. 查找同 AK + toolType 的 ONLINE 记录
    AgentConnection existing = repo.findByAkIdAndToolTypeAndStatus(ak, toolType, ONLINE);
    if (existing != null) {
        // 踢旧连接（标记 OFFLINE + 关闭 WebSocket）
        repo.updateStatus(existing.getId(), OFFLINE);
        eventRelayService.removeAgentSession(existing.getId());
    }
    // 2. 每次都创建新记录
    AgentConnection agent = AgentConnection.builder()...build();
    repo.insert(agent);
    return agent;
}
```

## 5. 当前已知问题

### 5.1 安全风险：认证参数暴露在 URL 中

URL Query Parameter 中的 `ak`、`sign` 等敏感信息会被：

- Web 服务器访问日志（Nginx access log）记录
- 代理服务器（如 v2rayN）日志记录
- 浏览器历史记录（如果通过浏览器调试）
- CDN/ALB 日志记录

### 5.2 身份不稳定：每次注册都创建新记录

当前 `register()` 每次都执行 `INSERT`，导致：

- 同一设备重启 OpenCode 后获得不同的 `agentId`
- 历史会话关联断裂
- `agent_connection` 表数据膨胀

### 5.3 连接策略不当：踢旧替新

当前策略是"踢掉已有连接，接受新连接"。这意味着：

- 恶意或误操作的新连接可以挤掉正在工作的旧连接
- 正在进行中的会话会被突然中断

### 5.4 缺少注册超时

WebSocket 握手成功后，没有限制 `register` 消息的到达时间。一个只做握手不发 register 的连接会持续占用资源。

### 5.5 缺少设备绑定

AK 没有与特定设备绑定，理论上任何知道 AK/SK 的客户端都可以连接。
