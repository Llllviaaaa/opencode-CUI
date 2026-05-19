## Summary

v2 已覆盖 v1 的 6 个主要问题，生产代码 callsite 的 12 / 6 / 4 数量也和 `rg` 核对一致。未发现新的 Critical，但还有 **Major=2 / Minor=1**，主要是签名迁移和 scope gating 规格不够可执行。GitNexus / ABCoder MCP 调用本轮均返回 `user cancelled`，以下结论基于本地 PRD、research 和源码 grep。

## v1 Issue 修复 verification

| v1 Issue | 结论 | 依据 |
|---|---|---|
| [Critical] R5 freeze 语义 | FIXED | v2 明确 first 入 pending 冻结，legacy String overload 显式 resolve，DB/plain fallback 传 null。 |
| [Critical] callsite matrix | FIXED | PRD 列出 A/B/C = 12/6/4；本地 `rg` 核对生产代码一致。 |
| [Major] action guard | FIXED | 决策 10、R4、AC11 都要求仅 CHAT 传 list，reply/create/close/abort 为 null。 |
| [Major] case B 入口 | FIXED | v2 单独列出 IM/External case B personal，并纳入 AC13。 |
| [Major] string[] 严格校验 | FIXED | R1 改为 `readTree + isArray + isTextual`，AC12 覆盖 `[1,true]` / `[{}]` / `[null]`。 |
| [Minor] delete cache TTL | FIXED | Goal 与风险 5 明确 delete 依赖 5min TTL，立即生效需走 update/disable。 |

## New Issues

### [Major] `PendingChatRequest` / `fromSessionFallback` 签名迁移没有覆盖全部源码和测试 callsite

- **Where**: PRD B matrix / R5；source: `SessionRebuildService.java:197,422,566`，tests: 20 个 `new PendingChatRequest(`、9 个 `fromSessionFallback(`。
- **Why**: v2 只列了生产代码 6 个 `new PendingChatRequest`，但新增 record 字段会让现有 test 构造点编译失败，替换 `fromSessionFallback(session,text)` 也会影响源码和测试的旧签名调用。
- **Fix**: 明确二选一：保留 8 参 constructor 和 2 参 `fromSessionFallback(...)->allowedSlashCommands=null` 兼容重载；或把所有源码 + test callsite 加入迁移矩阵并逐一更新。

### [Major] A7/B2 在 business/default scope 也会先 resolve，违反“仅 personal scope”的执行边界

- **Where**: PRD R4 A7+B2，AC7；source `InboundProcessingService.dispatchChatToGateway` 当前由 `appendToPending=false` 表示 business/default 不应入 pending。
- **Why**: PRD 示例在 `dispatchChatToGateway` 顶端无条件 `resolver.resolve(businessDomain, sessionType)`。即使下游 4 参 builder 忽略 list，也会在 business/default 热路径读 sysconfig、打 WARN，并让 “仅 personal” 变成“仅 personal 下发但所有 scope 查询”。
- **Fix**: resolve 必须 lazy/gated：只有确定 `action == CHAT && scope == personal` 时调用。IM/External case C 可用 `appendToPending == true` 作为 personal 判定；miniapp route 需用 3 参 dispatcher 判定最终 scope 后再 resolve。

### [Minor] `InvokeCommand` 兼容 constructor 保留要求写得不够硬

- **Where**: PRD R3。
- **Why**: 当前源码有 5/6/8 参兼容 constructor，测试里有 62 个 `new InvokeCommand(`。PRD snippet 只展示 9 参 secondary，容易被实现成替换式改动。
- **Fix**: R3 明确“保留现有 5/6/8 参 constructors；新增 9 参 secondary；10 参 canonical”，并让 `InvokeCommandTest` 断言各旧 constructor 的 `allowedSlashCommands == null`。

## Decision

- v1 修复：6/6 已覆盖
- v2 新增 Critical: 0
- v2 新增 Major: 2
- v2 新增 Minor: 1
- Recommendation: **NEEDS_REVISION**

Must-fix before PR1: 补齐 `PendingChatRequest/fromSessionFallback` 全 callsite 迁移策略；给 A7/B2 加 personal scope gating，避免 business/default scope 读 sysconfig。