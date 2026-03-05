---
phase: 1
plan: 1
---

# Plan 1.1 Summary — Plugin 入口重构 + 事件接收

## 执行时间
2026-03-06

## 完成内容

### Task 1: 创建 Plugin 入口点 + 事件过滤
- 创建 `plugin.ts` — 新的 OpenCode Plugin 入口点，使用 `PlatformAgent` 常量导出
- 创建 `types/PluginTypes.ts` — 本地 Plugin 类型定义（因 @opencode-ai/plugin 包尚未发布）
- 创建 `EventFilter.ts` — `shouldRelay()` 函数区分 15+ 种中继事件 vs 本地事件
- 创建 `PermissionMapper.ts` — `mapPermissionResponse()` 实现 allow→once 映射

### Task 2: 重构 EventRelay + GatewayConnection 适配
- 重构 `EventRelay.ts`:
  - 移除 SSE 自订阅，改为 `relayUpstream(event)` 推送模式
  - 使用 `OpencodeClient` 替代 `OpenCodeBridge` 处理下行操作
  - 已提前实现 `permission_reply` 和 `close_session` invoke 处理
- 重构 `HealthChecker.ts`: 使用 `OpencodeClient` 替代 `OpenCodeBridge`
- 更新 `ProtocolAdapter.ts`: import 从 OpenCodeBridge 改为 PluginTypes
- 删除 `PcAgentPlugin.ts` (被 plugin.ts 替代)
- 删除 `OpenCodeBridge.ts` (被 ctx.client 替代)
- 更新 `package.json`: main → plugin.ts, 添加 test 脚本

## 验证
- `npx tsc --noEmit` — ✅ 零错误通过

## 风险记录
- `@opencode-ai/plugin` 包不存在 → 使用本地类型定义解决
