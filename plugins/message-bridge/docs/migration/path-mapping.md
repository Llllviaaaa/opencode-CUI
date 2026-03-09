# Message-Bridge 文档路径映射

**Version:** 1.1  
**Date:** 2026-03-07  
**Status:** Active  
**Owner:** message-bridge maintainers  
**Related:** `../README.md`, `../AGENTS.md`, `../operations/change-log.md`

## 生效日期

- 2026-03-07

## 路径映射

| 旧路径 | 新路径 | 生效日期 | 备注 |
|---|---|---|---|
| `docs/architecture/solution-design.md` | `docs/design/solution-design.md` | 2026-03-07 | 迁移到实现方案层 |
| `docs/architecture/message-bridge-implementation.md` | `docs/design/implementation-plan.md` | 2026-03-07 | 迁移到实施计划层 |
| `docs/architecture/prd-traceability.md` | `docs/quality/traceability-matrix.md` | 2026-03-07 | 迁移到质量证据层 |
| `docs/architecture/test-validation.md` | `docs/quality/test-strategy.md` | 2026-03-07 | 迁移到测试策略层 |
| `docs/architecture/architecture-validation.md` | `docs/quality/validation-report.md` | 2026-03-07 | 迁移到验证报告层 |

## 兼容规则

发现旧路径引用时，必须替换为新路径并同步更新本文件记录。

补充说明（更正）：runtime 已采用 Bun-only 基线，插件入口契约为 `PluginInput -> Hooks`。
