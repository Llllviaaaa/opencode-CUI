---
phase: 1
plan: 1
wave: 1
---

# Plan 1.1: Plugin 入口重构 + 事件接收

## Objective
将 PC Agent 从独立启动的 class (`PcAgentPlugin`) 重构为标准 OpenCode Plugin 格式。建立新的 Plugin 入口点，通过 event hook 接收 OpenCode 事件，并通过 GatewayConnection 上行中继到 AI-Gateway。

## Context
- .gsd/SPEC.md
- .gsd/phases/1/RESEARCH.md
- src/main/pc-agent/PcAgentPlugin.ts (将被替换)
- src/main/pc-agent/OpenCodeBridge.ts (将被移除)
- src/main/pc-agent/EventRelay.ts (将被重构)
- src/main/pc-agent/GatewayConnection.ts (将被适配)
- src/main/pc-agent/HealthChecker.ts (将被适配)
- src/main/pc-agent/config/AgentConfig.ts
- src/main/pc-agent/package.json

## Tasks

<task type="auto">
  <name>创建 Plugin 入口点 + 重构事件接收</name>
  <files>
    src/main/pc-agent/plugin.ts (新建)
    src/main/pc-agent/package.json (修改)
  </files>
  <action>
    1. 在 package.json 中添加 `@opencode-ai/plugin` 依赖（类型定义包）
    2. 创建 `plugin.ts` 作为新的 Plugin 入口：
       ```ts
       import type { Plugin } from "@opencode-ai/plugin"
       export const PlatformAgent: Plugin = async (ctx) => {
         // 初始化 GatewayConnection (传入 config)
         // 初始化 HealthChecker (使用 ctx.client 获取健康状态)
         // 连接 Gateway
         return {
           event: async ({ event }) => {
             // 过滤事件：只处理对话相关事件
             // 通过 EventRelay 上行中继到 Gateway
           }
         }
       }
       ```
    3. 事件过滤逻辑（plugin.ts 内或单独模块）：
       - 需中继: `message.*`, `permission.*`, `session.*`, `file.edited`, `todo.updated`, `command.executed`
       - 不中继: `tui.*`, `pty.*`, `installation.*`, `server.*`, `lsp.*`, `vcs.*`, `file.watcher.*`
    4. 更新 `package.json` 的 `main` 字段指向 `plugin.ts`

    **避免：**
    - 不要在 Plugin 中调用 `createOpencodeClient()`，使用 `ctx.client`
    - 不要自行注册 event.subscribe()，Plugin hook 自动接收
  </action>
  <verify>
    npx tsc --noEmit
    # 确保导出 PlatformAgent 符合 Plugin 类型
  </verify>
  <done>
    - plugin.ts 导出 PlatformAgent，类型为 Plugin
    - 事件过滤函数能正确区分中继/忽略的事件类型
    - package.json main 指向 plugin.ts 且包含 @opencode-ai/plugin 依赖
  </done>
</task>

<task type="auto">
  <name>重构 EventRelay + GatewayConnection 适配 Plugin 生命周期</name>
  <files>
    src/main/pc-agent/EventRelay.ts (重构)
    src/main/pc-agent/GatewayConnection.ts (修改)
    src/main/pc-agent/HealthChecker.ts (修改)
    src/main/pc-agent/PcAgentPlugin.ts (标记废弃/删除)
    src/main/pc-agent/OpenCodeBridge.ts (标记废弃/删除)
  </files>
  <action>
    1. **EventRelay 重构:**
       - 移除 `subscribeToOpenCode()` / SSE 事件订阅逻辑
       - 改为暴露 `relayUpstream(event: Event)` 方法，由 Plugin event hook 调用
       - 保留 `handleDownstream(message)` 逻辑（处理 Gateway→OpenCode invoke 命令）
       - 下行操作改为使用传入的 `OpencodeClient` (ctx.client) 而非 `OpenCodeBridge`

    2. **GatewayConnection 修改:**
       - 移除对 `PcAgentPlugin.start/stop` 生命周期的依赖
       - 暴露 `connect()` / `disconnect()` / `send()` 方法
       - `onMessage` 回调改为由 EventRelay 注册
       - 保持 WebSocket 连接管理、心跳、重连逻辑不变

    3. **HealthChecker 修改:**
       - 移除对 `OpenCodeBridge` 的依赖
       - 改为接受 `OpencodeClient` 参数做健康检查
       - 保持定期上报 `agent_online/offline` 逻辑

    4. **移除旧模块:**
       - 删除 `PcAgentPlugin.ts`（被 plugin.ts 替代）
       - 删除 `OpenCodeBridge.ts`（被 ctx.client 替代）

    **避免：**
    - 不要修改 AkSkAuth.ts — 它已经完全符合协议
    - 不要修改 AgentConfig.ts — 配置结构保持不变
    - GatewayConnection 的核心 WebSocket 逻辑（认证、心跳、重连）保持不变
  </action>
  <verify>
    npx tsc --noEmit
    # 确认无编译错误
    # 确认 PcAgentPlugin.ts 和 OpenCodeBridge.ts 已删除
    # 确认 EventRelay 不再有 SSE 订阅代码
  </verify>
  <done>
    - EventRelay 暴露 relayUpstream(event) 供 Plugin hook 调用
    - EventRelay 下行操作使用 OpencodeClient 而非 OpenCodeBridge
    - GatewayConnection 可独立 connect/send，不依赖 PcAgentPlugin
    - HealthChecker 使用 OpencodeClient 代替 OpenCodeBridge
    - PcAgentPlugin.ts 和 OpenCodeBridge.ts 已删除
    - TypeScript 编译通过
  </done>
</task>

## Success Criteria
- [ ] 新 Plugin 入口 `plugin.ts` 导出 `PlatformAgent`
- [ ] 事件过滤正确区分 15+ 种中继事件 vs 不中继事件
- [ ] EventRelay 从 SSE 自订阅改为由 Plugin hook 驱动
- [ ] GatewayConnection/HealthChecker 不再依赖旧模块
- [ ] 旧入口 PcAgentPlugin.ts 和 OpenCodeBridge.ts 已移除
- [ ] `npx tsc --noEmit` 编译通过
