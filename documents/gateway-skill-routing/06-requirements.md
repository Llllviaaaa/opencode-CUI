# Gateway 上游路由需求

> 版本：1.2  
> 日期：2026-03-12

## 1. 背景

`gateway` 已不再只服务 `skill-server`。未来会有多个“能力类似 `skill-server`”的上游服务并行接入，因此路由模型必须从单上游假设升级为多上游隔离模型。

## 2. 必须满足的需求

### 2.1 来源隔离

- `gateway` 必须识别每条消息所属的上游服务
- 上游消息必须显式携带 `source`
- 握手绑定的 `source` 与消息体 `source` 必须一致

### 2.2 回流隔离

- OpenCode 回流必须回到原始 `source` 所属服务
- 严禁把新服务的回流发送给 `skill-server`
- 严禁把 `skill-server` 的回流发送给其他服务

### 2.3 Owner 路由

- 路由必须先按 `source` 分服务域
- 再在对应服务域内按 owner 选择目标 gateway 实例
- owner key 统一采用 `source:instanceId`

### 2.4 Fallback 规则

- 原 owner 不可用时，只允许在同一 `source` 域内 fallback
- 同域无目标时允许失败
- 严禁跨域兜底

### 2.5 Agent 边界

- `pc-agent` 不感知 `source`
- `source` 只属于上游服务与 `gateway` 之间的标准协议，以及 `gateway` 内部路由上下文

### 2.6 可观测性

- 关键路由日志必须带 `traceId`
- 关键路由日志必须带 `source`
- 关键路由日志必须带 `ownerKey`
- 关键路由日志必须带 `routeDecision`
- 关键路由日志必须带 `fallbackUsed`
- 关键路由日志必须带 `errorCode`

## 3. 当前实现结果

- `GatewayMessage` 已新增 `source` 与 `traceId`
- `gateway` 已实现握手绑定 `source`
- `gateway` 已实现消息阶段 `source` 一致性校验
- Redis owner 注册已升级为按 `source` 分域
- 回流已升级为 `source + owner` 路由
- 同域 fallback 已落地，跨域 fallback 已禁止

## 4. 后续阶段边界

本阶段只解决多上游来源隔离与回流正确归属。

以下内容留给后续阶段：

- 跨服务全局唯一 session id 算法
- 会话级长期标识升级
- 多上游服务并发共享同一业务会话时的更细粒度隔离
