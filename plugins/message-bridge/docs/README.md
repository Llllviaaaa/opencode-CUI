# Message-Bridge 文档入口

**Version:** 2.2  
**Date:** 2026-03-10  
**Status:** Active  
**Owner:** message-bridge maintainers  
**Related:** `./AGENTS.md`, `./migration/path-mapping.md`

`plugins/message-bridge/docs` 的统一入口。

## 信息架构

- `product/`：需求基线（做什么）
- `architecture/`：架构原则、边界与 ADR（为什么）
- `design/`：实现方案与接口契约（怎么做）
- `quality/`：追踪矩阵、测试策略、验证证据（如何证明）
- `operations/`：发布流程与文档治理
- `migration/`：旧路径到新路径映射

## 按角色阅读

### 产品 / 需求

1. [需求文档 PRD](./product/prd.md)
2. [需求追踪矩阵](./quality/traceability-matrix.md)
3. [架构验证报告](./quality/validation-report.md)

### 架构

1. [架构总览](./architecture/overview.md)
2. [源码目录说明](./architecture/source-layout.md)
3. [ADR 索引](./architecture/adr/README.md)
4. [方案设计](./design/solution-design.md)

### 开发

1. [方案设计](./design/solution-design.md)
2. [实施计划](./design/implementation-plan.md)
3. [协议契约](./design/interfaces/protocol-contract.md)
4. [端到端消息链路说明](./design/interfaces/end-to-end-message-flow.md)
5. [配置契约](./design/interfaces/config-contract.md)
6. [源码目录说明](./architecture/source-layout.md)

### 测试

1. [测试策略](./quality/test-strategy.md)
2. [需求追踪矩阵](./quality/traceability-matrix.md)
3. [验证报告](./quality/validation-report.md)

## 治理与变更

1. [文档治理规范](./AGENTS.md)
2. [发布检查清单](./operations/release-checklist.md)
3. [日志可观测性手册](./operations/logging-reference.md)
4. [变更日志](./operations/change-log.md)
5. [路径映射](./migration/path-mapping.md)
