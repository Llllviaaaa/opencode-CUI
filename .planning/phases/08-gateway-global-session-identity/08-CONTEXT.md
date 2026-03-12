# Phase 8: 统一 Snowflake ID 基础设施治理 - Context

**Gathered:** 2026-03-12
**Status:** Ready for research

<domain>
## Phase Boundary

Phase 8 的目标已从“全局唯一 Session ID 与双服务验证”重新定义为：

`skill-server + gateway` 的统一 Snowflake ID 基础设施治理。

本 phase 关注的是两边主键与核心业务标识生成体系的统一雪花化改造，而不是继续扩展 Phase 7 的 `source` 隔离能力。Phase 7 已经完成多 `source` 路由与错投保护，本 phase 在此基础上统一 ID 基础设施，消除数据库自增主键依赖。
</domain>

<decisions>
## Implementation Decisions

### Phase 8 新目标
- `skill-server` 与 `gateway` 统一采用 Snowflake ID 作为主键基础设施
- 本 phase 不再局限于狭义 session id，而是覆盖两边几乎所有当前依赖自增主键的实体
- 目标是建立统一的 ID 生成规则、配置约束和落地方式

### Snowflake 基础设施共享方式
- 当前 phase 不强制抽离公共 module
- 先在仓库内分别在 `skill-server` 与 `gateway` 中落同一套 Snowflake 实现
- 两边必须保持完全一致的规则与配置语义
- 后续再择机提炼成共享组件

### 两边必须统一的 Snowflake 要素
- bit layout
- epoch
- worker / instance bits
- sequence bits
- 时钟回拨策略
- 配置项名称与配置语义

### 覆盖范围
- 本 phase 采用强治理范围
- 覆盖 `skill-server + gateway` 中几乎所有当前使用自增主键的实体
- 允许包含：
  - 表结构主键策略调整
  - 插入逻辑调整
  - mapper / repository 调整
  - 外键与关联引用调整
  - 测试数据与测试断言调整
  - 文档与配置同步

### Snowflake 分配策略
- 采用同一套 Snowflake 算法
- bit layout 中显式预留 `service bits`
- `skill-server` 与 `gateway` 各自占一个固定 `service code`
- 在 service code 之上再分配实例级 `worker/instance bits`
- 不采用纯人工范围切分作为主策略
- 本 phase 不引入动态 worker 抢占或独立发号服务

### skill-server 中的 welinkSessionId
- `welinkSessionId` 继续作为 skill 会话主键
- 但生成方式改为 Snowflake ID
- 不再使用数据库自增主键
- 不额外再拆出一个独立数据库主键字段

### 存量数据迁移策略
- 当前环境为测试环境
- 不做复杂的历史数据兼容迁移
- 直接清理现有数据
- 按新的 Snowflake 主键策略重新建数
- 不保留长期双轨兼容方案

### Claude's Discretion
- Snowflake 的具体 bit 位数分配
- `service code` 在配置中的命名形式
- 时钟回拨时采用 fail-fast、短暂等待还是告警后拒绝
- 两边 Snowflake 实现的具体封装类名与初始化方式
</decisions>

<specifics>
## Specific Ideas

- 用户明确要求 Phase 8 应升级为“统一 Snowflake ID 基础设施治理”，而不是停留在单一 session id 方案
- 用户明确要求 `skill-server` 和 `gateway` 使用同一套 Snowflake 规则
- 用户明确接受当前先复制同一实现到两个服务，后续再提炼
- 用户明确要求覆盖范围采用强治理模式，而不是只做局部实体
- 用户明确说明当前仍处于测试环境，可以清理存量数据并按新规则重建
</specifics>

<code_context>
## Existing Code Insights

### Existing ID Usage Patterns
- [`skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java): 大量逻辑直接使用 `Long sessionId / welinkSessionId`
- [`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java): 会话创建后依赖 `welinkSessionId <-> toolSessionId` 映射
- [`ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java): 当前协议层已稳定承载 `welinkSessionId` 与 `toolSessionId`

### Relevant Constraints from Phase 7
- `source` 已经成为上游服务与 `gateway` 的标准协议字段
- 路由已经按 `source + owner` 隔离
- `pc-agent` 不感知 `source`
- 因此 Phase 8 不需要再通过“跨 source 全局唯一”来兜底路由，重点转向统一 ID 基础设施

### Likely Impact Areas
- `skill-server` 的 session/message/permission/stream 等主实体及关联表
- `gateway` 中使用数据库主键或持久化实体的核心对象
- 各自 mapper / repository / test fixture / SQL DDL
</code_context>

<deferred>
## Deferred Ideas

- 将两边复制的 Snowflake 实现提炼为真正共享的公共 module
- 更进一步建设独立发号服务或动态 worker 分配能力
- 在后续 phase 中统一更多跨服务基础设施规范
</deferred>

---

*Phase: 08-gateway-global-session-identity*
*Context gathered: 2026-03-12*
