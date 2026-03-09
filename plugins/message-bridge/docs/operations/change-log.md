# Message-Bridge 文档变更日志

**Version:** 1.1  
**Date:** 2026-03-07  
**Status:** Active  
**Owner:** message-bridge maintainers  
**Related:** `../migration/path-mapping.md`, `../README.md`

## 2026-03-09

### Changed

- `design/interfaces/protocol-contract.md`：重写为当前生效协议（移除 `envelope`，统一 `welinkSessionId/toolSessionId`，新增 `abort_session`，`close_session -> session.delete`，`question_reply` 改为 question API）。
- `architecture/overview.md`：新增“协议对齐更正（2026-03-09）”说明，并修订上行职责描述。
- `product/prd.md`：新增协议对齐更正小节，标注当前实现口径。

## 2026-03-07

### Added

- `operations/logging-reference.md`（新增日志可观测性手册，包含日志级别语义、字段字典、关键路径 Mermaid 时序图、全事件清单与排障指引）

### Changed

- `docs/README.md`：在“治理与变更”中加入日志手册入口。
- `../README.md`：`Structured Logging` 小节增加日志手册链接。

## 2026-03-07（文档重构）

### Added

- `architecture/adr/README.md`
- `architecture/adr/0001-doc-information-architecture.md`
- `design/interfaces/protocol-contract.md`
- `design/interfaces/config-contract.md`
- `operations/release-checklist.md`
- `operations/change-log.md`
- `migration/path-mapping.md`
- `AGENT.md`（兼容入口）

### Changed

- 重写 `AGENTS.md`，新增目录路由、语言统一规范与检查项。
- 重写 `README.md`，统一中文入口与角色阅读路径。

### Moved

- `architecture/solution-design.md` -> `design/solution-design.md`
- `architecture/message-bridge-implementation.md` -> `design/implementation-plan.md`
- `architecture/prd-traceability.md` -> `quality/traceability-matrix.md`
- `architecture/test-validation.md` -> `quality/test-strategy.md`
- `architecture/architecture-validation.md` -> `quality/validation-report.md`

## 2026-03-07 文档口径更正（最小差异）

说明：
1. 本次采用“恢复原文 + 最小修订”策略。
2. 非必要内容未改动。

| 文件 | 更正章节 | 更正前口径（简述） | 更正后口径（简述） | 原因 |
|---|---|---|---|---|
| `product/prd.md` | §8 增补小节 | 缺少当前 runtime 基线与插件入口契约明确说明 | 增补：Bun-only 基线、`PluginInput -> Hooks`、上行/下行边界、`REQ-MB-CONN-002` backlog | 与实现/契约一致 |
| `architecture/overview.md` | §1.1、§3.2.4、§8、§10.1 | 默认 `node:test`，并将 `pongTimeoutMs` 视为已实现判定链路 | 测试口径改为 `bun test`；`pongTimeoutMs` 改为 backlog 未实现；补充 `event hook` 与 runtime 边界 | 与实现一致 |
| `quality/test-strategy.md` | §1.4 | `node:test` 作为主测试框架 | `bun test` 作为主测试框架 | 与当前脚本一致 |
| `quality/traceability-matrix.md` | §9.3 相关行 | `node:test` + `c8` | `bun test` + coverage gate 脚本 | 与当前脚本一致 |
| `README.md` | 首页说明 | 未声明当前 runtime/插件契约基线 | 补充 Bun-only 与 `PluginInput -> Hooks` | 降低阅读歧义 |
| `migration/path-mapping.md` | 文末补充 | 仅有路径迁移规则 | 补充当前 runtime 与插件契约口径 | 避免迁移说明与实现脱节 |
| `quality/validation-report.md` | §7 | Node + c8 证据口径 | Bun test + coverage gate 证据口径 | 与当前执行方式一致 |

## 2026-03-07 覆盖门禁口径更正（Bun）

说明：
1. 本次仅做覆盖门禁可执行性修订，不调整运行时与架构边界。
2. 目标是避免 `branches` 在 `BRF=0` 场景下出现“假通过”或“不可执行”。

| 文件 | 更正章节 | 更正前口径（简述） | 更正后口径（简述） | 原因 |
|---|---|---|---|---|
| `../scripts/check-coverage.mjs` | 覆盖阈值判定 | `lines + branches` 同时硬门禁；`BRF=0` 直接失败 | `lines>=80` 硬门禁；`BRF=0` 输出 `coverage_branch_unavailable` 告警并通过 | Bun 覆盖报告在当前环境分支统计不可用 |
| `product/prd.md` | §9.3 | `branches>=70%` 作为当前硬门禁 | `branches>=70%` 标注为当前观测项（非 CI 阻塞） | 文档与执行口径一致 |
| `quality/test-strategy.md` | §7.1 | 覆盖率表将 branch 作为 PR 阻塞项 | 保留 branch 目标值，但改为观测项 | 与当前脚本行为一致 |
| `quality/traceability-matrix.md` | §9.3 相关行 | Branch gate 标记为 Implemented | 改为 Planned / Observable | 防止状态误报 |
