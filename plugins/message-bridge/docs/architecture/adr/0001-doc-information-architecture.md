# ADR-0001：采用分层文档信息架构

**Version:** 1.1  
**Date:** 2026-03-07  
**Status:** Accepted  
**Owner:** message-bridge maintainers  
**Related:** `../../AGENTS.md`, `../../README.md`, `../../migration/path-mapping.md`

## Context

原 `docs/architecture/` 混合了架构、设计、实施计划和验证文档，导致可发现性差、职责边界不清晰。

## Decision

采用分层文档结构：

- `product/`
- `architecture/` 与 `architecture/adr/`
- `design/` 与 `design/interfaces/`
- `quality/`
- `operations/`
- `migration/`

并通过 `docs/AGENTS.md` 的路由规则、元数据规则与语言规范统一约束。

## Alternatives Considered

1. 仅保留 `product/ + architecture/`：拒绝，职责混杂问题无法根治。
2. 单目录 + 标签分类：拒绝，可读性与治理可执行性不足。

## Consequences

1. 按角色检索路径更清晰。
2. 旧路径引用需要迁移并登记。
3. 新文档必须遵守目录路由与语言规范。
