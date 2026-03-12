# Milestone Audit: v1.1 Connection-Aligned Consumption Fix

## Audit Result

通过

## Original Intent

本 milestone 的目标是修复多实例 `skill-server / gateway` 场景下，消息被非真实连接实例消费，导致 miniapp 看不到 opencode 实时返回的问题。

## Evidence Reviewed

- [PROJECT.md](D:/02_Lab/Projects/sandbox/opencode-CUI/.planning/PROJECT.md)
- [REQUIREMENTS.md](D:/02_Lab/Projects/sandbox/opencode-CUI/.planning/REQUIREMENTS.md)
- [ROADMAP.md](D:/02_Lab/Projects/sandbox/opencode-CUI/.planning/ROADMAP.md)
- [STATE.md](D:/02_Lab/Projects/sandbox/opencode-CUI/.planning/STATE.md)
- [06-SUMMARY.md](D:/02_Lab/Projects/sandbox/opencode-CUI/.planning/phases/06-skill-server-gateway-opencode/06-SUMMARY.md)
- [06-UAT.md](D:/02_Lab/Projects/sandbox/opencode-CUI/.planning/phases/06-skill-server-gateway-opencode/06-UAT.md)
- [09-connection-aligned-consumption-fix.md](D:/02_Lab/Projects/sandbox/opencode-CUI/documents/gateway-skill-routing/09-connection-aligned-consumption-fix.md)
- [10-v1.1-integration-checklist.md](D:/02_Lab/Projects/sandbox/opencode-CUI/documents/gateway-skill-routing/10-v1.1-integration-checklist.md)

## Delivered

- `GatewayMessage` 顶层补齐 `userId`
- `skill-server` invoke 时携带可信 `userId`
- `gateway` 基于 `ak -> userId` 做 invoke 校验，并在回流时补回 `userId`
- `pc-agent` 出入站报文不再携带 `userId`
- `skill-server` 实时广播从 `session:{sessionId}` 切换到 `user-stream:{userId}`
- `SkillStreamHandler` 负责用户级首订阅 / 末退订
- 旧 `session` 级实时广播路径已退出主链路
- 已补齐联调清单与 v1.1 设计说明

## Validation

- 自动化测试通过：
  - `ai-gateway`: `GatewayMessageTest`, `EventRelayServiceTest`
  - `skill-server`: `SkillMessageControllerTest`, `SkillSessionControllerTest`, `GatewayRelayServiceTest`, `SkillStreamHandlerTest`
- 真实 UAT 通过：
  - 初始发现“miniapp 不实时显示、刷新后才出现”
  - 已定位为 `SkillStreamHandler` 双重清理导致的误退订
  - 修复后用户复测通过

## Requirements Assessment

审计结论认为以下 requirement 已满足：

- `BUG-01`
- `BUG-02`
- `BUG-03`
- `VER-01`
- `VER-02`

说明：`REQUIREMENTS.md` 中这些条目仍显示为旧状态，属于文档残留，不影响本次 milestone 的完成判定。

## Residual Notes

- `documents/gateway-skill-routing/README.md` 仍主要描述 v1.0 基线，但 v1.1 变更已单独沉淀，不构成阻塞
- 若继续演进 v2，建议将 A/B/C 多实例联调步骤进一步脚本化

## Recommendation

v1.1 可以进入 milestone 完成流程。
