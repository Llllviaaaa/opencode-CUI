# 代码结构

## 目录布局

```text
.
├── ai-gateway/            Gateway 服务，负责 Agent 接入与路由
├── skill-server/          Skill 服务，负责会话、消息、推流、持久化
├── skill-miniapp/         React + Vite 前端小程序
├── src/main/pc-agent/     PC Agent 插件
├── documents/             设计、协议、实现方案文档
├── .opencode/             工具相关状态目录
├── package.json           根级最小清单
└── VERSION                版本标记
```

## 目录职责

- **`ai-gateway/`**：管理 PC Agent 长连接、AK/SK 鉴权、设备绑定、在线状态、跨实例转发。
- **`skill-server/`**：管理 Skill 会话、消息存储、实时流协议、Gateway 回连与 IM 转发。
- **`skill-miniapp/`**：负责用户交互、历史消息加载、流式渲染、权限请求交互。
- **`src/main/pc-agent/`**：把本地 OpenCode SDK 事件与远端 Gateway 协议互相转换。
- **`documents/`**：保存分专题的设计包，包括协议、路由、身份认证、流式消息设计等。

## 关键文件位置

- **根目录**
  - 根清单：`package.json`
  - 锁文件：`package-lock.json`
  - 版本文件：`VERSION`
  - 项目级规则：`AGENTS.md`
- **Gateway**
  - 启动类：`ai-gateway/src/main/java/com/opencode/cui/gateway/GatewayApplication.java`
  - 配置：`ai-gateway/src/main/resources/application.yml`
  - WebSocket 配置：`ai-gateway/src/main/java/com/opencode/cui/gateway/config/GatewayConfig.java`
  - 核心 Handler：`ai-gateway/src/main/java/com/opencode/cui/gateway/ws/`
  - 核心 Service：`ai-gateway/src/main/java/com/opencode/cui/gateway/service/`
  - MyBatis XML：`ai-gateway/src/main/resources/mapper/`
  - 迁移脚本：`ai-gateway/src/main/resources/db/migration/`
- **Skill Server**
  - 启动类：`skill-server/src/main/java/com/opencode/cui/skill/SkillServerApplication.java`
  - 配置：`skill-server/src/main/resources/application.yml`
  - Controller：`skill-server/src/main/java/com/opencode/cui/skill/controller/`
  - WebSocket：`skill-server/src/main/java/com/opencode/cui/skill/ws/`
  - 核心 Service：`skill-server/src/main/java/com/opencode/cui/skill/service/`
  - MyBatis XML：`skill-server/src/main/resources/mapper/`
  - 迁移脚本：`skill-server/src/main/resources/db/migration/`
- **Miniapp**
  - 启动入口：`skill-miniapp/src/main.tsx`
  - 应用骨架：`skill-miniapp/src/app.tsx`
  - 页面：`skill-miniapp/src/pages/`
  - Hook：`skill-miniapp/src/hooks/`
  - 协议适配层：`skill-miniapp/src/protocol/`
  - UI 组件：`skill-miniapp/src/components/`
  - API 封装：`skill-miniapp/src/utils/api.ts`
- **PC Agent**
  - 插件入口：`src/main/pc-agent/plugin.ts`
  - 连接管理：`src/main/pc-agent/GatewayConnection.ts`
  - 鉴权：`src/main/pc-agent/AkSkAuth.ts`
  - 协议桥接：`src/main/pc-agent/EventRelay.ts`
  - 测试：`src/main/pc-agent/__tests__/`

## 命名约定

- **Java 包名**：统一采用 `com.opencode.cui.gateway` 与 `com.opencode.cui.skill`。
- **Java 类名**：按职责后缀区分，例如 `Controller`、`Service`、`Repository`、`Config`、`Handler`。
- **React 组件文件名**：使用 PascalCase，例如 `ConversationView.tsx`、`PermissionCard.tsx`。
- **Hook 文件名**：统一使用 `useX` 形式，例如 `useSkillStream.ts`、`useSkillSession.ts`。
- **协议层文件名**：按角色命名，例如 `OpenCodeEventParser.ts`、`StreamAssembler.ts`。
- **迁移文件名**：使用 `V#__description.sql` 风格。

## 新代码应该放在哪里

- **新增 Gateway 能力**
  - 接口层：`ai-gateway/.../controller` 或 `ai-gateway/.../ws`
  - 编排逻辑：`ai-gateway/.../service`
  - 持久化：`ai-gateway/.../repository` + XML Mapper + 迁移脚本
- **新增 Skill Server 能力**
  - REST 合约：`skill-server/.../controller`
  - 事件流与协议翻译：`GatewayRelayService.java`、`OpenCodeEventTranslator.java`
  - 数据落库：`SkillMessageService.java`、`MessagePersistenceService.java`、对应 Mapper 和迁移
- **新增 Miniapp 能力**
  - 协议解析：`skill-miniapp/src/protocol/`
  - 网络/状态：`skill-miniapp/src/hooks/`
  - 视图渲染：`skill-miniapp/src/components/` 或 `skill-miniapp/src/pages/`
- **新增 PC Agent 能力**
  - 连接与鉴权：`src/main/pc-agent/GatewayConnection.ts`、`AkSkAuth.ts`
  - 事件桥接：`EventRelay.ts`、`EventFilter.ts`、`PermissionMapper.ts`

## 特殊目录

- **`documents/`**：不是普通附件目录，而是项目架构和协议的重要知识库。
- **`documents/obsolete/`**：保存旧版本设计与历史资料，阅读时要注意时效性。
- **`skill-miniapp/src/protocol/AGENTS.md`**：该子目录有额外工作规则，修改协议层文件前需要遵守。
- **`node_modules/`**：仓库根下已存在依赖目录，搜索和映射时应默认排除。

*映射时间：2026-03-11。*
