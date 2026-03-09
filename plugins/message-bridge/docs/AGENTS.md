<!-- Parent: ../../AGENTS.md -->

# Message-Bridge 文档治理规范

**Version:** 2.1
**Date:** 2026-03-07
**Status:** Active
**Owner:** message-bridge maintainers
**Related:** `./README.md`, `./migration/path-mapping.md`, `./AGENT.md`
**Scope:** `plugins/message-bridge/docs/`

## 目的

本规范用于统一 `message-bridge` 插件文档的信息架构、语言风格、命名规则与跨文档引用，确保后续新增文档具备一致性与可维护性。

## 需求基线

| 文件 | 角色 |
|---|---|
| `docs/product/prd.md` | 唯一需求基线（定义做什么） |

## 核心规则

1. 架构、设计、质量、运维文档必须与 `docs/product/prd.md` 对齐。
2. 不得在 PRD 冻结范围外新增需求结论。
3. 如实现与 PRD 不一致，先记录差异项，不得静默改写需求结论。
4. 文档范围仅限插件；涉及 `ai-gateway` / `skill-server` 改造必须标注为外部依赖。
5. 协议字段命名必须与 PRD 一致，兼容字段需显式说明。

## 文档语言统一规范

1. `message-bridge/docs` 的默认文档语言统一为**简体中文**。
2. 以下内容允许保留英文：
   - 协议字段名、类型名、错误码、事件名、action 名
   - 路径、命令、配置键、环境变量、代码片段
   - 第三方官方专有名词（如 `OpenCode`、`WebSocket`、`ADR`）
3. 同一文档中，叙述性正文不得中英文混写同义概念；首次出现可“中文术语（English）”并在后文保持一致。
4. 新增文档必须使用中文叙述；历史英文文档在修改时应一并完成中文化。

## 目录白名单

`docs/` 下仅允许以下一级目录：

- `product/`
- `architecture/`
- `design/`
- `quality/`
- `operations/`
- `migration/`

## 文档类型路由表

| 文档类型 | 目录 | 说明 |
|---|---|---|
| 需求范围、验收、NFR | `product/` | 定义做什么，不写实现细节 |
| 架构原则、边界、数据流、状态模型 | `architecture/` | 定义为什么这样设计 |
| 架构决策记录（ADR） | `architecture/adr/` | 一条决策一篇文档 |
| 实施方案、模块拆分、接口说明 | `design/` | 定义怎么实现 |
| 协议/配置契约 | `design/interfaces/` | 面向实现与联调 |
| 追踪矩阵、测试策略、验证报告 | `quality/` | 定义如何证明可交付 |
| 发布检查、变更治理 | `operations/` | 定义如何发布与维护 |
| 路径迁移映射 | `migration/` | 记录旧路径到新路径 |

## 元数据要求

`docs/` 下每篇 Markdown 顶部必须包含：

- `Version`
- `Date`
- `Status`
- `Owner`
- `Related`

## 命名与格式要求

1. 文件名必须为小写 `kebab-case`：`^[a-z0-9-]+\\.md$`。
2. 命名白名单文件：`README.md`、`AGENTS.md`、`AGENT.md`（仅限目录入口/规范文件）。
3. 禁止中文文件名、数字前缀（如 `01-`）、版本号后缀（如 `-v1.4`）。
4. 版本与状态写入元数据，不写入文件名。
5. 标题层级使用 `#` / `##` / `###`。
6. 协议说明优先“表格 + JSON 示例”。
7. 参数说明必须包含默认值、单位、是否可配置。

## 范围章节要求

新增或大幅更新的 `architecture/design/quality` 文档必须包含：

- `In Scope`
- `Out of Scope`
- `External Dependencies`

并明确：

1. 本轮仅插件侧范围。
2. 不改服务端业务逻辑。
3. 幂等与一致性由服务端负责。

## 引用与迁移规则

1. 文档引用优先使用新路径。
2. 发生路径迁移时，必须同步更新 `docs/migration/path-mapping.md`。
3. 已存在替代路径时，禁止继续引用旧路径。

## 提交前检查清单

- [ ] 对齐 `docs/product/prd.md`
- [ ] 明确 In Scope / Out of Scope / External Dependencies
- [ ] 未引入未冻结需求
- [ ] 协议字段与 PRD 一致（含兼容字段）
- [ ] 包含可执行验收标准
- [ ] 元数据包含 Version/Date/Status/Owner/Related
- [ ] 文件名符合 kebab-case
- [ ] 新文档目录与路由表一致
- [ ] 路径变化已登记 `docs/migration/path-mapping.md`
- [ ] 叙述性正文已统一为简体中文

## 相关文档

- 根规范：`../../AGENTS.md`
- 兼容入口：`./AGENT.md`
- 文档入口：`./README.md`
- 需求基线：`./product/prd.md`
- 架构总览：`./architecture/overview.md`
- 迁移映射：`./migration/path-mapping.md`
