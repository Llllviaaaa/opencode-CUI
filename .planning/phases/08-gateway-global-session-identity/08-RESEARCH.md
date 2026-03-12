# Phase 8: 统一 Snowflake ID 基础设施治理 - Research

**Researched:** 2026-03-12  
**Status:** Ready for planning

## Standard Stack

### 1. 继续使用现有 Spring Boot + MyBatis + Flyway，不引入外部发号服务
- **Use:** 在 `skill-server` 与 `ai-gateway` 内各自落同一套 Snowflake 实现，通过应用内组件生成主键。
- **Why:** 当前 milestone 已明确为测试环境，允许清理数据重建；而且两边现有持久化链路都基于 MyBatis + Flyway，最自然的落点是应用侧生成 id，再由 mapper 直接写入。
- **Verified with:** [`SkillSessionMapper.xml`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/mapper/SkillSessionMapper.xml)、[`SkillMessageMapper.xml`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/mapper/SkillMessageMapper.xml)、[`SkillMessagePartMapper.xml`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/mapper/SkillMessagePartMapper.xml)、[`AgentConnectionMapper.xml`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/resources/mapper/AgentConnectionMapper.xml) 当前都依赖 MyBatis `insert`
- **Confidence:** High

### 2. 采用应用侧预分配 Snowflake ID，移除 `AUTO_INCREMENT` 与 `useGeneratedKeys`
- **Use:** 所有纳入本 phase 的主键在 Java 服务层或统一的 `IdGenerator` 组件中先生成，再插入数据库。
- **Why:** 当前 schema 与 mapper 广泛依赖数据库自增和回填主键。若目标是统一 Snowflake 基础设施，最直接的做法就是从应用侧生成 id，并把 mapper 从 “等 DB 回填” 改成 “显式写入主键”。
- **Verified with:** [`V1__skill.sql`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/db/migration/V1__skill.sql)、[`V2__message_parts.sql`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/db/migration/V2__message_parts.sql)、[`V1__gateway.sql`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/resources/db/migration/V1__gateway.sql) 均使用 `AUTO_INCREMENT`；上述 4 个 mapper 均使用 `useGeneratedKeys="true"`
- **Confidence:** High

### 3. 两边先复制同一 Snowflake 实现，保持规则完全一致
- **Use:** 在 `skill-server` 与 `ai-gateway` 各自增加同版本 Snowflake 生成器实现和配置类。
- **Why:** Phase 8 的讨论结果已锁定“先复制同一实现到两个服务，后续再提炼”；因此 planning 应避免在本 phase 中额外引入模块拆分重构。
- **Verified with:** [`08-CONTEXT.md`](D:/02_Lab/Projects/sandbox/opencode-CUI/.planning/phases/08-gateway-global-session-identity/08-CONTEXT.md)
- **Confidence:** High

## Architecture Patterns

### Pattern A: 单机应用内 Snowflake 生成器 + 配置驱动的 service bits

**Recommended flow**
1. 在 `skill-server` 与 `ai-gateway` 分别引入同一份 `SnowflakeIdGenerator`
2. 通过配置声明：
   - `epoch`
   - `serviceCode`
   - `workerId`
   - bit layout
3. 生成器输出 `long`
4. 业务服务在构建实体时提前填充 `id`
5. mapper 直接写入 `id`，不再使用 `useGeneratedKeys`

**Why this pattern fits current code**
- 当前两边都是典型的 Spring Service -> Repository -> MyBatis mapper 路径
- [`SkillSessionService.createSession`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java) 和 [`AgentRegistryService.register`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/AgentRegistryService.java) 都是天然的 id 生成切入点
- 业务层已经广泛使用 `Long` 主键，Snowflake `long` 可以保持 Java 类型兼容

**Confidence:** High

### Pattern B: 先改“主实体 + 直接外键引用 + 创建链路”，再向边缘实体扩展

**Recommended flow**
- 第一批优先改造：
  - `skill_session.id`
  - `skill_message.id`
  - `skill_message_part.id`
  - `agent_connection.id`
  - `ak_sk_credential.id`
- 同步处理所有直接引用这些主键的 `session_id` / `message_id`
- 再排查其余表与实体，统一迁移到同一规则

**Why this pattern is still compatible with强治理**
- 讨论结果要求覆盖几乎所有自增主键实体，但 planning 仍需要按依赖顺序落地，避免一次性并发修改所有表导致验证失控
- 当前 `skill_message_part.message_id` 显式注释为 `FK to skill_message.id`，说明迁移顺序必须考虑依赖方向

**Confidence:** High

### Pattern C: 测试环境下采用“清数 + 重建 + 全量回归”而非双轨迁移

**Recommended flow**
1. 增加新的 Flyway migration，将主键与相关索引/外键调整为 Snowflake 策略
2. 清空测试环境数据
3. 用新规则重新建数
4. 跑全量业务链路回归

**Why**
- 用户已明确当前仍处于测试环境，允许清理现有数据
- 这避免了双轨兼容字段、历史 id 映射表、复杂回填脚本等额外工程成本

**Confidence:** High

### Pattern D: service code + worker id 的统一 bit layout

**Recommended flow**
- 在 bit layout 中显式预留 `service bits`
- `skill-server` 与 `ai-gateway` 各自固定 `serviceCode`
- `workerId` 在各服务内部按实例配置
- 所有生成器共享同一 epoch 与 sequence 规则

**Why**
- 用户明确选择这种方式，而不是仅靠人工分配 workerId 范围
- 这能够从算法结构上保证跨服务不冲突，同时保留实例级扩展空间

**Confidence:** High

## Don't Hand-Roll

### 1. 不要继续依赖数据库自增与 `useGeneratedKeys`
- **Avoid:** 保留 `AUTO_INCREMENT`，只是在业务侧“尽量不用”
- **Why:** 这会导致系统长期处于两套主键策略混用状态，与本 phase 目标冲突

### 2. 不要在本 phase 引入独立发号服务
- **Avoid:** 为了统一 Snowflake 基础设施再新建网络发号服务
- **Why:** 当前项目仍处于测试环境，且两边都是单体服务结构，外部发号服务会显著扩大 phase 范围

### 3. 不要只改表结构，不改服务层创建逻辑
- **Avoid:** 只把表去掉 `AUTO_INCREMENT`，却保留服务层“先 insert 再拿回填 id”的逻辑
- **Why:** 当前创建链路明显依赖回填主键，例如 `SkillSessionService.createSession`、`SkillMessageService.saveMessage`、`AgentRegistryService.register`

### 4. 不要把 `serviceCode` 只写进文档，不落实到配置和测试
- **Avoid:** 仅口头规定 `skill-server` 与 `gateway` 使用不同 service code
- **Why:** 这种约束如果没有配置项与测试覆盖，后续极易被修改或误配

## Common Pitfalls

### 1. `skill_message_part.message_id` 的类型与语义容易被忽视
- **Risk:** [`V2__message_parts.sql`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/db/migration/V2__message_parts.sql) 中 `message_id` 是 `BIGINT`，并注释为 `FK to skill_message.id`；若只改主表主键，关联表会失配
- **Prevention:** planning 必须把 `skill_message` 与 `skill_message_part` 作为同一批迁移对象
- **Confidence:** High

### 2. 现有业务代码对 “Long sessionId” 依赖很深
- **Risk:** `skill-server` 大量 service/controller/stream 逻辑直接以 `Long sessionId` 作为业务主键；若 bit layout 或生成时机处理不当，会影响全链路
- **Prevention:** 继续保持 Java 侧 `Long` 类型，优先做到“语义变化，类型不变”
- **Confidence:** High

### 3. `gateway` 的 `agent_connection` 有“重用现有记录”逻辑
- **Risk:** [`AgentRegistryService.register`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/AgentRegistryService.java) 不是每次都创建新记录，而是可能复用现有记录；若 planning 只关注 insert，不关注 reuse/update，会漏验
- **Prevention:** planning 需要分别验证“新建”与“复用”路径，不把 gateway 主键治理误解成纯插入场景
- **Confidence:** High

### 4. 消息协议里的 `welinkSessionId` 仍然是外部可见字段
- **Risk:** 虽然主键改为 Snowflake 不改变 Java 类型，但它仍是协议对外字段；测试需要覆盖创建、回流、流式广播、权限回复等链路
- **Prevention:** planning 需要覆盖 `SkillSessionController`、`GatewayRelayService`、`SkillStreamHandler` 相关协议回归
- **Confidence:** High

### 5. 复制实现容易在两边悄悄漂移
- **Risk:** 用户已接受“先复制同一实现”，但如果没有同一份参数说明和统一测试约束，两边后续会分叉
- **Prevention:** research 建议 planning 中明确增加“同一 bit layout / epoch / rollback policy 的镜像测试”
- **Confidence:** High

## Code Examples

### Example 1: `skill_session` 当前是“DB 自增 + service 拿回填 id”

**Current anchors**
- [`V1__skill.sql`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/db/migration/V1__skill.sql)
- [`SkillSessionMapper.xml`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/mapper/SkillSessionMapper.xml)
- [`SkillSessionService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java)

**Recommended change**
- migration 去掉 `AUTO_INCREMENT`
- service 创建 session 前先生成 Snowflake `id`
- mapper insert 直接写入 `id`

**Why**
- 这是 `welinkSessionId` 雪花化的主入口

### Example 2: `skill_message` / `skill_message_part` 是主键回填依赖链

**Current anchors**
- [`SkillMessageMapper.xml`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/mapper/SkillMessageMapper.xml)
- [`SkillMessagePartMapper.xml`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/resources/mapper/SkillMessagePartMapper.xml)
- [`SkillMessageService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java)
- [`MessagePersistenceService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java)

**Recommended change**
- `skill_message.id` 与 `skill_message_part.id` 同步改为 Snowflake
- 保持 `message_id` 与 `part_id` 这类协议级字符串字段独立存在
- 先生成 DB 主键，再走持久化

**Why**
- 避免“主表 Snowflake、关联表还在等 DB 回填”的混合状态

### Example 3: `gateway` 当前至少有 `agent_connection` 和 `ak_sk_credential` 两类自增主键

**Current anchors**
- [`V1__gateway.sql`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/resources/db/migration/V1__gateway.sql)
- [`V2__ak_sk_credential.sql`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/resources/db/migration/V2__ak_sk_credential.sql)
- [`AgentConnectionMapper.xml`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/resources/mapper/AgentConnectionMapper.xml)
- [`AkSkCredentialMapper.xml`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/resources/mapper/AkSkCredentialMapper.xml)

**Recommended change**
- gateway 中纳入强治理范围的实体也去掉自增主键
- 重点关注 `insert + findById + latestByAkId` 等依赖路径

**Why**
- Phase 8 已明确是 `skill-server + gateway` 的统一 ID 基础设施治理，而不只是 skill 侧局部改造

## Suggested Planning Focus

在后续 `$gsd-plan-phase 8` 中，建议至少拆成 4 个关注点：

1. **统一 Snowflake 规则与配置**
- 两边复制同一生成器实现
- 固定 epoch、service bits、service code、worker bits、sequence bits
- 统一时钟回拨策略与配置项

2. **`skill-server` 主键体系改造**
- `skill_session`
- `skill_message`
- `skill_message_part`
- 相关 service / mapper / DDL / 测试回归

3. **`gateway` 主键体系改造**
- `agent_connection`
- `ak_sk_credential`
- 相关 service / mapper / DDL / 测试回归

4. **测试环境重建与全链路验证**
- 清理现有数据
- 重建 schema 与测试数据
- 验证创建、更新、回流、流式链路、查询排序与复用逻辑

---

*Phase: 08-gateway-global-session-identity*  
*Research completed: 2026-03-12*
