---
phase: 1
plan: 3
wave: 2
---

# Plan 1.3: 单元测试 + Bun 兼容性验证

## Objective
为 Phase 1 重构后的所有核心模块编写单元测试，验证协议适配、事件过滤、权限映射等逻辑。确保代码在 Bun 运行时下编译和执行正确。

## Context
- .gsd/SPEC.md
- .gsd/phases/1/RESEARCH.md (§4 Bun 兼容性)
- src/main/pc-agent/ProtocolAdapter.ts
- src/main/pc-agent/AkSkAuth.ts
- src/main/pc-agent/EventRelay.ts
- src/main/pc-agent/plugin.ts (Plan 1.1 创建的)
- src/main/pc-agent/package.json

## Tasks

<task type="auto">
  <name>编写核心模块单元测试</name>
  <files>
    src/main/pc-agent/__tests__/ProtocolAdapter.test.ts (新建)
    src/main/pc-agent/__tests__/AkSkAuth.test.ts (新建)
    src/main/pc-agent/__tests__/EventFilter.test.ts (新建)
    src/main/pc-agent/__tests__/PermissionMapper.test.ts (新建)
    src/main/pc-agent/package.json (修改 — 添加 test 脚本和测试依赖)
  </files>
  <action>
    1. 在 package.json 中添加测试依赖和脚本：
       - `bun:test` (Bun 内置测试框架) 或 `vitest` 作为测试运行器
       - 推荐使用 Bun 内置 `bun test`，因为目标运行时就是 Bun
       - 添加 `"test": "bun test"` 脚本

    2. **ProtocolAdapter 测试:**
       - 测试 `wrapEvent()` 输出格式包含正确的 type/sessionId/event/envelope 结构
       - 测试 envelope 的 sequenceNumber 自增
       - 测试 `wrapToolDone()`, `wrapToolError()`, `wrapSessionCreated()` 输出格式
       - 测试 `unwrapMessage()` 正确解析 invoke 命令

    3. **AkSkAuth 测试:**
       - 测试 `sign()` 生成有效的 HMAC-SHA256 签名
       - 测试相同输入产生相同签名（确定性）
       - 测试不同 SK 产生不同签名

    4. **EventFilter 测试:**
       - 测试所有中继事件类型 (message.*, permission.*, session.*, 等) 返回 true
       - 测试所有非中继事件类型 (tui.*, pty.*, lsp.*, 等) 返回 false
       - 边界: 未知事件类型默认行为

    5. **PermissionMapper 测试:**
       - 测试 `"allow"` → `"once"` 映射
       - 测试 `"always"` → `"always"` 映射
       - 测试 `"deny"` → `"reject"` 映射
       - 测试无效输入的错误处理

    **避免：**
    - 不要 mock WebSocket 连接 — 那是集成测试的范畴
    - 不要测试 GatewayConnection 的连接逻辑 — 第 5 阶段端到端验证
    - 测试应是纯逻辑测试，不依赖网络
  </action>
  <verify>
    cd src/main/pc-agent && bun test
    # 所有测试通过
  </verify>
  <done>
    - 4 个测试文件: ProtocolAdapter, AkSkAuth, EventFilter, PermissionMapper
    - 覆盖所有上行消息格式、签名、事件过滤、权限映射逻辑
    - 所有测试在 Bun 下通过
  </done>
</task>

<task type="auto">
  <name>Bun 运行时兼容性验证</name>
  <files>
    src/main/pc-agent/tsconfig.json (可能需要调整)
    src/main/pc-agent/package.json (确认)
  </files>
  <action>
    1. 确保 tsconfig.json 配置兼容 Bun：
       - `"module": "ESNext"` 或 `"module": "Preserve"`
       - `"moduleResolution": "bundler"` 或 `"moduleResolution": "node16"`
       - `"target": "ESNext"`

    2. 运行 Bun typecheck:
       ```bash
       cd src/main/pc-agent && bun run typecheck
       ```

    3. 验证关键 Node.js 内置模块在 Bun 下可用：
       - `node:crypto` — createHmac, randomUUID
       - `node:os` — hostname, platform
       - `ws` — WebSocket client

    4. 如果有不兼容问题，记录在 .gsd/phases/1/ISSUES.md 中

    **避免：**
    - 不要安装 Bun polyfill 包 — Bun 原生支持 node: 协议
  </action>
  <verify>
    cd src/main/pc-agent && bun run typecheck
    # 无错误
    cd src/main/pc-agent && bun test
    # 所有测试通过
  </verify>
  <done>
    - TypeScript 在 Bun 下编译无错误
    - node:crypto, node:os 在 Bun 下正常工作
    - 所有单元测试在 Bun 下通过
  </done>
</task>

## Success Criteria
- [ ] 4 个单元测试文件覆盖核心逻辑
- [ ] `bun test` 所有测试通过
- [ ] `bun run typecheck` 编译无错误
- [ ] node:crypto HMAC-SHA256 在 Bun 下有效
- [ ] 无 Bun 兼容性问题（或问题已记录并有 workaround）
