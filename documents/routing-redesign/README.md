# Gateway/Source Routing Redesign Docs

## Phase 1 Finalization Set

Phase 1 的正式设计产物由以下三份文档组成：

- [01-current-routing-reality.md](./01-current-routing-reality.md)
- [02-target-routing-model-v3.md](./02-target-routing-model-v3.md)
- [03-legacy-compatibility-and-acceptance.md](./03-legacy-compatibility-and-acceptance.md)

这三份文档共同定义了“当前怎么工作、目标怎么重构、legacy 怎么兼容以及如何验收”。

## Reading Order

建议按下面的顺序阅读：

1. 先看 `01-current-routing-reality.md`，理解当前代码和现网拓扑下的真实链路与冲突点。
2. 再看 `02-target-routing-model-v3.md`，理解正式目标模型、共享索引、协议和连接池语义。
3. 最后看 `03-legacy-compatibility-and-acceptance.md`，收口 legacy 边界、失败恢复场景和设计验收口径。

也就是：现状 -> 目标模型 -> 兼容/验收。

## Scope Boundary

`Phase 1 is design-only`。

这意味着本阶段的职责是把正式设计基线写清楚，而不是立即改造生产代码。真正的代码改造、灰度策略和回归验证应该进入后续 `implementation phase`；容量验证、吞吐验证和高并发场景验收则应进入后续 `load test phase`。

如果后面需要继续拆 rollout、观测性、draining 等主题，也应在 Phase 1 之后按独立 phase 推进，而不是回填到设计文档里。
