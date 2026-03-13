---
status: testing
phase: 10-stream-event-affinity
source:
  - implementation-diff
  - 10-01-PLAN.md
  - 10-02-PLAN.md
  - 10-03-PLAN.md
started: 2026-03-12T16:34:23.1389407+08:00
updated: 2026-03-12T16:34:23.1389407+08:00
---

## Current Test

number: 1
name: A/B 两个会话的实时回答不会串流
expected: |
  同一用户同时打开两个不同的会话 A 和 B。只在 A 中发送一条会触发流式回答的消息时，
  只有 A 会看到文本增量、工具状态、问题卡片或完成态更新；B 应保持不变，不会出现 A 的回答片段。
awaiting: user response

## Tests

### 1. A/B 两个会话的实时回答不会串流
expected: 同一用户同时打开两个不同的会话 A 和 B。只在 A 中发送一条会触发流式回答的消息时，只有 A 会看到文本增量、工具状态、问题卡片或完成态更新；B 应保持不变，不会出现 A 的回答片段。
result: pending

### 2. 刷新或重新进入会话后，恢复态仍回到正确会话
expected: 当某个会话存在正在进行中的流式内容或刚完成的流式内容时，刷新页面或重新进入该会话后，snapshot / streaming 恢复态仍只出现在对应会话中；不会把 A 的恢复态带到 B。
result: pending

### 3. 其它会话不会收到当前会话的结束态或错误态
expected: 当 A 会话完成一次回答、进入 idle，或者出现可见错误/重试提示时，这些状态变化只会出现在 A；B 不会无故出现结束提示、错误提示或重试状态。
result: pending

## Summary

total: 3
passed: 0
issues: 0
pending: 3
skipped: 0

## Gaps
