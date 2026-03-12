---
status: testing
phase: 08-gateway-global-session-identity
source:
  - 08-01-SUMMARY.md
  - 08-02-SUMMARY.md
  - 08-03-SUMMARY.md
  - 08-04-SUMMARY.md
started: 2026-03-12T14:12:00+08:00
updated: 2026-03-12T14:12:00+08:00
---

## Current Test

number: 1
name: Skill Session 创建返回 Snowflake welinkSessionId
expected: |
  在测试环境调用 skill-server 的创建会话接口后，返回的 welinkSessionId 应该是一个可直接作为 Long 使用的数值型 ID。
  它不应为空、不应是字符串格式的业务前缀 ID，也不应出现依赖数据库自增才能回填的异常。
awaiting: user response

## Tests

### 1. Skill Session 创建返回 Snowflake welinkSessionId
expected: 在测试环境调用 skill-server 的创建会话接口后，返回的 welinkSessionId 应该是一个可直接作为 Long 使用的数值型 ID；不为空、不带业务前缀、不出现主键回填异常。
result: pending

### 2. 会话创建后的消息与回流链路保持正常
expected: 使用新创建的 welinkSessionId 继续发送消息、接收流式回流或工具事件时，链路应正常工作；不会因为主键生成方式变更出现查不到 session、消息无法持久化或回流错投。
result: pending

### 3. 测试环境清库重建后双服务仍保持唯一 ID 治理
expected: 按文档执行测试环境清库重建并重启 skill-server 与 ai-gateway 后，新写入记录应继续使用 Snowflake Long 主键；不同服务的记录不会因为 ID 策略冲突而互相串线。
result: pending

## Summary

total: 3
passed: 0
issues: 0
pending: 3
skipped: 0

## Gaps

