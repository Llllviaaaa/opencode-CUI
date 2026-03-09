# 协议对齐需求清单

> 基于 `documents/protocol` 目录下的 5 份协议规范文档
> 对比 skill-server、ai-gateway、pc-agent、skill-miniapp 全部实现代码
> 日期：2026-03-09

---

## 差异汇总

| #   | 层  | 严重级别 | 差异简述                                                        | 状态     | 处理方式          |
| --- | --- | -------- | --------------------------------------------------------------- | -------- | ----------------- |
| 1   | ①   | 🔴 高     | `userId` 类型 `Long` vs 协议 `String`                           | ✅ 已修复 | 修改代码          |
| 2   | ①   | 🔴 高     | 会话列表 API 缺少 `imGroupId` / `ak` 过滤参数                   | ✅ 已修复 | 修改代码          |
| 3   | ①   | 🔴 高     | 会话列表 API 参数名 `statuses` vs 协议 `status`                 | ✅ 已修复 | 修改代码          |
| 4   | ①   | 🟡 中     | `SkillSession.Status` 包含 `IDLE`，协议仅定义 `ACTIVE`/`CLOSED` | 📝 待执行 | 更新协议          |
| 5   | ①   | 🟡 中     | Miniapp `StreamMessage.sessionId` vs 协议 `welinkSessionId`     | 📝 待执行 | 修改前端代码      |
| 6   | ①   | 🟡 中     | Miniapp `getSessions()` 未传过滤参数                            | ⏭️ 跳过   | 暂不修改          |
| 7   | ①   | 🟡 中     | Miniapp `Session` 类型缺少字段                                  | 📝 待执行 | 修改前端代码      |
| 8   | ①   | 🟡 中     | WebSocket 端点路径                                              | ✅ 已确认 | 无需修改          |
| 9   | ①   | 🟢 低     | `closeSession` 响应 `welinkSessionId` 类型                      | ✅ 已关闭 | 无需修改          |
| 10  | ②   | 🟡 中     | `agentOnline` 多传了 `toolType`/`toolVersion`                   | 📝 待执行 | 更新协议          |
| 11  | ②   | 🟡 中     | `toolError` 工厂方法多余 `welinkSessionId` 参数                 | 📝 待执行 | 修改 Gateway 代码 |
| 12  | ②   | 🟢 低     | Gateway REST API 路径                                           | ✅ 已确认 | 无需修改          |
| 13  | ③   | 🟢 低     | `GatewayMessage` 包含协议外字段                                 | ✅ 保留   | 不改动            |
| 14  | ①   | 🟢 低     | `PageResult` 字段名 `totalElements`/`number` vs `total`/`page`  | 📝 待执行 | 加 @JsonProperty  |
| 15a | ①   | 🟢 低     | 非协议 API：`GET /definitions`                                  | 📝 待执行 | 删除代码          |
| 15b | ①   | 🟢 低     | 非协议 API：`POST /send-to-im`                                  | 📝 待执行 | 加入协议          |
| 15c | ①   | 🟢 低     | 非协议 API：`GET /agents` 响应格式不规范                        | 📝 待执行 | 改代码 + 加入协议 |
