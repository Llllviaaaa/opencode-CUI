---
description: 使用 Pragmatic Clean Code Reviewer 进行代码审查
---

# 代码审查工作流

使用基于 Clean Code / Clean Architecture / The Pragmatic Programmer 三本经典书籍的 350+ 规则对代码进行严格审查。

## 步骤

1. **阅读 Skill 指令**
   使用 `view_file` 工具阅读 `.agents/skills/pragmatic-clean-code-reviewer/SKILL.md` 的完整内容，了解审查规则和流程。

2. **确定项目定位**
   向用户询问以下三个问题来确定项目的严格等级（L1-L5）：
   - Q1: 谁会使用这份代码？（D1 Solo / D2 Internal / D3 External）
   - Q2: 你想要什么标准？（R1 Ship / R2 Normal / R3 Careful / R4 Strict）
   - Q3: 代码有多关键？（仅在特定条件下询问）

   根据 SKILL.md 中的 Quick Lookup Table 查出对应的等级。

3. **识别审查目标**
   确认要审查的文件范围。可以是：
   - 用户指定的文件或目录
   - 最近修改的文件（通过 `git diff` 获取）
   - 整个模块或功能区域

4. **识别语言范式**
   根据 `references/language-adjustments.md` 确定代码语言的范式类型，调整规则适用性。

5. **执行 15 点审查清单**
   按照 SKILL.md 中的 15-Point Review Checklist 逐项检查代码：
   - 正确性与功能
   - 可读性与可维护性
   - 设计与架构
   - 测试
   - 高级检查（L3+ 才需要）

   审查过程中参考 `references/` 目录下的各规则文件获取详细指导。

6. **生成审查报告**
   按照 SKILL.md 中的 Report Format 模板生成结构化的审查报告，包含：
   - 🔴 关键问题（必须修复）
   - 🟡 重要问题（应该修复）
   - 🔵 次要问题（锦上添花）
   - ✅ 代码亮点
   - 📝 最终判定

   每个问题必须引用具体的规则编号（CC-##、CA-##、PP-##）。
