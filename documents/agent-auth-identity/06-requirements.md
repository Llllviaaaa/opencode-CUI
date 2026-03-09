# 需求文档

> 版本：1.0  
> 日期：2026-03-09

## 1. 背景

当前系统的 PCAgent 认证方式存在以下问题：

- 认证参数（AK、signature）通过 URL Query Parameter 传递，会被 Nginx/ALB/代理日志记录
- Agent 每次重启都会创建新的数据库记录，导致身份不稳定和数据膨胀
- 重复连接采用"踢旧替新"策略，正在进行中的会话可能被意外中断
- 缺少设备绑定校验和注册超时机制

## 2. 目标

### 2.1 核心目标

1. 将认证参数从 URL 迁移到安全通道，消除日志泄露风险
2. 实现 Agent 身份持久化，同一设备获得稳定的 agentId
3. 改为"保旧拒新"的连接策略，保护进行中的会话
4. 补齐设备绑定和注册超时等安全机制

### 2.2 具体需求

| 编号 | 需求                                               | 优先级 |
| ---- | -------------------------------------------------- | ------ |
| R1   | 认证信息通过 WebSocket 子协议传输，不暴露在 URL 中 | P0     |
| R2   | AK/SK 签名校验在握手阶段完成                       | P0     |
| R3   | 同一 AK+toolType 复用已有 `agent_connection` 记录  | P0     |
| R4   | 重复活跃连接时拒绝新连接、保留旧连接               | P0     |
| R5   | PC Agent 识别拒绝码（4409）并停止自动重连          | P0     |
| R6   | WebSocket 连接建立后 10 秒内未 register 自动断开   | P1     |
| R7   | 支持第三方设备绑定校验（AK + MAC + toolType）      | P2     |
| R8   | 设备绑定服务不可用时降级放行                       | P2     |
| R9   | 清理历史重复 agent_connection 记录                 | P1     |
| R10  | 唯一约束保证同一 AK+toolType 不会有多条记录        | P1     |

## 3. 非目标

本需求不包括：

- 修改 AK/SK 签名算法本身（仍使用 HMAC-SHA256）
- 修改 Gateway 与 Skill Server 之间的通信方式
- 新增 OAuth / JWT 等认证机制
- 前端 Miniapp 认证变更
- 修改心跳机制

## 4. 约束条件

### 4.1 基础设施

- MySQL 5.7.x
- Redis 5.x
- Spring Boot WebSocket
- Node.js `ws` 库

### 4.2 协议与实现

- WebSocket 子协议格式：`auth.{base64-json}`
- 自定义关闭码范围：4000-4999（RFC 6455 允许）
- 设备绑定服务默认关闭，需显式启用
- Flyway 管理数据库版本迁移

### 4.3 兼容性

- PC Agent 必须同步更新（不兼容旧版 URL 认证）
- Gateway 不再接受 URL Query Parameter 中的认证参数

## 5. 验收标准

### 5.1 必须满足

- AC-1：Gateway URL 日志中不包含 ak、sign 等敏感参数
- AC-2：PC Agent 发起的 WebSocket 连接使用 `Sec-WebSocket-Protocol` 传递认证信息
- AC-3：同一设备重启 OpenCode 后，`agentId` 保持不变
- AC-4：同时启动两个 OpenCode（同 AK），第二个收到 `register_rejected`，第一个不受影响
- AC-5：PC Agent 收到 4409 关闭码后不自动重连
- AC-6：Gateway 编译通过，全部单元测试通过
- AC-7：`agent_connection` 表有 `uk_ak_tooltype` 唯一约束

### 5.2 可选验收（P2 需求）

- AC-8：设备绑定校验启用后，AK 与未绑定 MAC 的连接被拒绝
- AC-9：设备绑定服务不可用时，连接降级放行

## 6. 受影响模块

- `ai-gateway`（认证、注册、数据模型、数据库）
- `src/main/pc-agent`（连接管理、插件入口）

不直接改造：

- `skill-server`
- `skill-miniapp`
- `test-simulator`
