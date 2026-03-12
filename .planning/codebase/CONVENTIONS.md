# 编码规范

## 命名模式

- **Java 后端按职责后缀命名**：
  - `*Controller`：REST 接口
  - `*Service`：业务编排
  - `*Repository`：持久化接口
  - `*Handler`：WebSocket 处理器
  - `*Config`：配置类
- **React 组件**使用 PascalCase，例如 `skill-miniapp/src/components/MessageBubble.tsx`。
- **Hook** 使用 `useX` 命名，例如 `skill-miniapp/src/hooks/useSkillStream.ts`。
- **协议字段**在多层之间命名保持一致，如 `messageId`、`partId`、`toolSessionId`、`welinkSessionId`、`permissionId`。
- **数据库迁移**统一采用 `V#__description.sql` 风格。

## 代码风格

- **Java**
  - 使用 4 空格缩进
  - 偏好构造函数注入
  - REST 返回统一采用 `ResponseEntity<ApiResponse<...>>`
  - 日志使用 `@Slf4j`
- **TypeScript**
  - `skill-miniapp` 与 `src/main/pc-agent` 基本采用 2 空格缩进
  - 偏好显式 `interface`、联合类型和规范化 helper
  - 常使用 early return 简化分支
  - PC Agent 文件中存在较多 JSDoc 文档注释
- 仓库里**没有发现**统一的 lint / format 配置，因此最重要的规则是**跟随所在文件的既有风格**。

## Import 组织

- TypeScript 一般先引入外部依赖，再引入本地模块。
- Miniapp 中会使用 `type` import 来区分纯类型依赖。
- Java 文件整体遵循 IDE / Spring 常见的 import 排序方式。

## 错误处理

- REST 层通常先做前置校验，再返回结构化错误，而不是把异常直接抛到最外层。
- WebSocket 层倾向于“记录日志 + 关闭非法连接 / 忽略非法消息”，避免协议噪音导致整体崩溃。
- 前端协议解析器倾向于**容错解析**，不会假设上游消息一定完美。
- PC Agent 中常见 `readString`、`readRecord` 这类安全读取 helper，避免对动态事件结构直接强取字段。

## 日志

- Java 代码大量使用占位符日志，便于结构化排查。
- 两个后端默认都对业务包开启较细粒度日志。
- PC Agent 在 `src/main/pc-agent/EventRelay.ts` 中维护了额外的本地调试日志文件。
- 日志消息通常包含 `userId`、`ak`、`sessionId`、`wsId` 等关键上下文字段。

## 注释

- 大型 Java 类通常有类级注释，用来解释协议职责和使用场景。
- PC Agent 公共接口周围的 JSDoc 较完整，示例也较多。
- Miniapp 注释较少，但会在协议边界和配置逻辑处补充说明。
- 整体上更依赖**清晰命名**而不是大量行内注释。

## 函数设计

- 常见风格是拆出**小型规范化函数**或**字段提取 helper**，例如：
  - `skill-miniapp/src/utils/api.ts`
  - `skill-miniapp/src/protocol/history.ts`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`
- 复杂状态不直接放在 Controller / Component 中，而是下沉到专门的 Service / Hook / Assembler。
- Hook 返回的是精简后的状态与动作接口，而不是把底层传输细节暴露给 UI。

## 模块设计

- **Miniapp**：Hook 管网络和状态，Component / Page 管呈现。
- **Skill Server**：Controller 管协议入口，Service 管编排，Repository 管落库。
- **Gateway**：连接路由与广播集中在 Service，不把路由逻辑散落在 Handler / Controller 中。
- **PC Agent**：鉴权、连接、事件桥接、权限映射被拆到独立文件。
- 整个仓库明显把**协议适配层**视为独立模块，而不是混进 UI 或普通业务层里。

*映射时间：2026-03-11。*
