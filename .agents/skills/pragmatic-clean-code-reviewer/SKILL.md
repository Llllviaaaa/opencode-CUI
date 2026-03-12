---
name: pragmatic-clean-code-reviewer
description: 基于 Clean Code / Clean Architecture / The Pragmatic Programmer 三本经典书籍的 350+ 规则进行严格代码审查
---

# Pragmatic Clean Code Reviewer

Strict code review following Clean Code, Clean Architecture, and The Pragmatic Programmer principles.

**Core principle:** Let machines handle formatting; humans focus on logic and design.

## ⚠️ MANDATORY FIRST STEP: Project Positioning

**STOP! Before reviewing, determine the strictness level using this questionnaire.**

### Q1: Who will use this code?
| Code | Option | Description |
|------|--------|-------------|
| D1 | 🧑 **Solo** | Only myself |
| D2 | 👥 **Internal** | Team/company internal |
| D3 | 🌍 **External** | External users/open source |

### Q2: What standard do you want?
| Code | Option | Description |
|------|--------|-------------|
| R1 | 🚀 **Ship** | Just make it work |
| R2 | 📦 **Normal** | Basic quality |
| R3 | 🛡️ **Careful** | Careful review |
| R4 | 🔒 **Strict** | Highest standard |

### Q3: How critical? (Conditional)
> **Only ask if:** (D2 or D3) AND (R3 or R4)

| Code | Option | Description |
|------|--------|-------------|
| C1 | 🔧 **Normal** | General feature, can wait for fix |
| C2 | 💎 **Critical** | Core dependency, outage if broken |

### Quick Lookup Table
| D | R | C | Level | Example |
|---|---|---|-------|---------|
| D1 | R1 | - | L1 | Experiment script |
| D1 | R2 | - | L1 | Personal utility |
| D1 | R3 | - | L2 | Personal long-term project |
| D1 | R4 | - | L3 | Personal perfectionist |
| D2 | R1 | - | L1 | Team prototype |
| D2 | R2 | - | L2 | Team daily dev |
| D2 | R3 | C1 | L2 | Internal helper tool |
| D2 | R3 | C2 | L3 | Internal SDK |
| D2 | R4 | C1 | L3 | Internal tool (high std) |
| D2 | R4 | C2 | L4 | Internal core infra |
| D3 | R1 | - | L2 | Product MVP |
| D3 | R2 | - | L3 | General product feature |
| D3 | R3 | C1 | L3 | Small OSS tool |
| D3 | R3 | C2 | L4 | Product core feature |
| D3 | R4 | C1 | L4 | OSS tool (high std) |
| D3 | R4 | C2 | L5 | Finance/Medical/Core OSS |

**For detailed explanations:** See [positioning.md](references/positioning.md)

---

## Level Definitions
| Level | Name | Key Question |
|-------|------|--------------|
| **L1** | 🧪 Lab | Does it run? |
| **L2** | 🛠️ Tool | Can I understand it next month? |
| **L3** | 🤝 Team | Can teammates take over? |
| **L4** | 🚀 Infra | Will others suffer if I break it? |
| **L5** | 🏛️ Critical | Can it pass audit? |

---

## Strictness Matrix & Metric Thresholds
**Quick reference:**
- Function length: L2(≤80) → L3(≤50) → L4(≤30) → L5(≤20)
- Parameter count: L2(≤7) → L3(≤5) → L4(≤3) → L5(≤2)
- Test coverage: L2(30%) → L3(60%) → L4(80%) → L5(95%)

**For complete matrices:** See [positioning.md](references/positioning.md#strictness-matrix)

### ⚠️ Measurement Rules (MUST follow)
1. **Count logic lines only** — exclude docstrings, comments, blank lines
2. **Metrics are conversation starters, not hard gates**
3. **Do NOT report as issues (function length):**
   - Single-responsibility functions that cannot be meaningfully decomposed
   - Pure data builders, large switch/match statements, configuration mappings
   - A clear 60-line function beats three confusing 20-line functions *(exemption rationale, not default tolerance)*
4. **Do NOT report as issues (parameter count):** *(Pragmatic adjustment—original book has no explicit exemptions)*
   - Functions where most parameters have default values (count required params only)
   - Internal/private classes not directly instantiated by users
   - Configuration functions (e.g., `configure_logging(level="INFO", ...)`)
   - Factory/Builder patterns controlled by framework
5. **Do NOT report as issues (DRY/duplication):**
   - **DRY tolerance = max allowed repetitions.** Report when occurrences **exceed** this number:
     - L5: max 1 → report on 2nd occurrence
     - L4: max 2 → report on 3rd occurrence
     - L3: max 3 → report on 4th occurrence
     - L2: max 4 → report on 5th occurrence
     - L1: N/A (no limit)
   - **Accidental duplication** *(all levels)*: Similar code representing different business knowledge—do NOT report even if exceeds tolerance. Quick test: "If one changes, must the other ALWAYS change?" If no → accidental duplication → keep separate.
   - **Same file** *(L1-L3 only)*: Duplicates within same file are lower risk
   - See [principles-spectrum.md](references/principles-spectrum.md) for DRY vs WET guidance

---

## Language-Aware Review
Before reviewing, identify the language paradigm:

| Paradigm | Languages | Clean Code Applicability |
|----------|-----------|-------------------------|
| Pure OOP | Java, C# | ✅ Full |
| Multi-paradigm | TypeScript, Python, Kotlin | ⚠️ Adjust |
| Functional | Haskell, Elixir, F# | ⚠️ Many rules don't apply |
| Systems | Rust, Go | ⚠️ Different patterns |

**For language-specific adjustments:** See [language-adjustments.md](references/language-adjustments.md)

---

## 15-Point Review Checklist

### 1. Correctness & Functionality
- [ ] **Logic implements requirements correctly?** (PP-75)
- [ ] **Boundary conditions and error handling complete?** (CC-153, PP-36)
- [ ] **Security vulnerabilities?** (PP-72, PP-73)

### 2. Readability & Maintainability
- [ ] **Names reveal intent?** (CC-4, PP-74)
- [ ] **Functions small and do one thing?** (CC-20, CC-21)
- [ ] **Comments explain "Why" not "What"?** (CC-39, CC-43)

### 3. Design & Architecture
- [ ] **Follows SRP?** (CA-8, CC-110)
- [ ] **Avoids duplication (DRY)?** (PP-15, CC-37)
- [ ] **Dependency direction correct?** (CA-12, CA-31)

### 4. Testing
- [ ] **New code has tests?** (PP-91, CC-194)
- [ ] **Tests readable and independent?** (CC-102, CC-106)

### 5. Advanced Checks (L3+)
- [ ] **Concurrency safe?** (PP-57, CC-137)
- [ ] **Security validated?** (PP-72, PP-73)
- [ ] **Resources released?** (PP-40)
- [ ] **Algorithm complexity appropriate?** (PP-63, PP-64)

---

## Common Code Smells
| Smell | Rule | Quick Check |
|-------|------|-------------|
| Long function | CC-20 | Exceeds level threshold? (See Metric Thresholds + Measurement Rules) |
| Too many params | CC-26, CC-147 | Exceeds level threshold? (See Metric Thresholds + Measurement Rules) |
| Magic numbers | CC-175 | Unnamed constants? |
| Feature envy | CC-164 | Using other class's data? |
| God class | CC-109, CA-8 | Multiple responsibilities? |
| Train wreck | CC-81, PP-46 | `a.b().c().d()`? |

**For full symptom lookup:** See [quick-lookup.md](references/quick-lookup.md)

---

## Red Flags - Investigate Further
> ⚠️ **Language-aware:** Some red flags are paradigm-dependent. Always check [language-adjustments.md](references/language-adjustments.md) first.

If you notice any of these, consult the reference files:

- Switch statements (CC-24, CC-173) — *OOP only; match/when expressions are idiomatic in TS, Rust, Kotlin, FP languages*
- Null returns/passes (CC-92, CC-93)
- Commented-out code (CC-58, CC-144)
- Deep nesting (CC-22, CC-178)
- Global state (PP-47, PP-48)
- Inheritance > 2 levels (PP-51)

---

## DO NOT Review (Machine's Job)
These should be caught by Linter/Formatter:

- Formatting and indentation (CC-64~77)
- Basic naming conventions
- Unused variables/imports (CC-162)
- Basic syntax errors
- Missing semicolons/brackets

**Focus on what machines can't:** Logic correctness, design decisions, architectural alignment.

---

## Report Format
> **Before reporting:** Apply Measurement Rules exemptions. Do NOT include exempt items (e.g., pure data builders exceeding line limits) in any issue category—omit them entirely.

```markdown
## 📋 Code Review Report

**Project Positioning:** [Level] (e.g., L3 Team)
**Review Scope:** [files/commits reviewed]

### 🔴 Critical Issues (Must Fix)
- [file:line] Issue description
  - **Rule:** XX-## (Rule Name)
  - **Principle:** Brief explanation of why this matters
  - **Suggestion:** How to fix it

### 🟡 Important Issues (Should Fix)
- [file:line] Issue description
  - **Rule:** XX-## (Rule Name)
  - **Principle:** Brief explanation of why this matters
  - **Suggestion:** How to fix it

### 🔵 Minor Issues (Nice to Have)
- [file:line] Issue description
  - **Rule:** XX-## (Rule Name)

### ✅ Strengths
- What's done well

### 📝 Verdict
[✅ Ready to merge / ⚠️ Needs fixes / 🚫 Major rework needed]
```

### Report Example

```markdown
## 📋 Code Review Report

**Project Positioning:** L3 Team
**Review Scope:** src/services/user.ts, src/utils/helpers.ts

### 🔴 Critical Issues (Must Fix)
- [user.ts:45] SQL query built with string concatenation
  - **Rule:** PP-72 (Keep It Simple and Minimize Attack Surfaces)
  - **Principle:** String concatenation in SQL queries creates injection vulnerabilities
  - **Suggestion:** Use parameterized queries: `db.query('SELECT * FROM users WHERE id = ?', [userId])`

### 🟡 Important Issues (Should Fix)
- [helpers.ts:120] Function `processUserData` has 8 parameters
  - **Rule:** CC-26 (Function Arguments) + CC-147 (Too Many Arguments)
  - **Principle:** Many parameters increase cognitive load and make testing difficult. L3 threshold is ≤5.
  - **Suggestion:** Group related parameters into a `UserDataOptions` object

- [user.ts:200] Duplicate validation logic (3rd occurrence)
  - **Rule:** PP-15 (DRY) + CC-37 (Don't Repeat Yourself)
  - **Principle:** L3 allows max 3 repetitions. This is the 3rd occurrence—consider extracting.
  - **Suggestion:** Extract to `validateUserInput(input)` function in utils

### 🔵 Minor Issues (Nice to Have)
- [helpers.ts:55] Magic number `86400`
  - **Rule:** CC-175 (Replace Magic Numbers with Named Constants)

### ✅ Strengths
- Clear separation between data access and business logic
- Consistent error handling pattern
- Good test coverage on core functions

### 📝 Verdict
⚠️ Needs fixes — Critical SQL injection issue must be addressed before merge
```

---

## Rule Reference Codes
| Prefix | Source | Reference |
|--------|--------|-----------|
| **PP-##** | The Pragmatic Programmer | [pragmatic-programmer.md](references/pragmatic-programmer.md) |
| **CC-##** | Clean Code | [clean-code.md](references/clean-code.md) |
| **CA-##** | Clean Architecture | [clean-architecture.md](references/clean-architecture.md) |

---

## Common Principles Quick Reference
| Acronym | Meaning | Rule |
|---------|---------|------|
| YAGNI | You Aren't Gonna Need It | PP-43 |
| KISS | Keep It Simple | CC-130, PP-72 |
| DRY | Don't Repeat Yourself | PP-15, CC-37 |
| SOLID | 5 Design Principles | CA-8~12 |
| LoD | Law of Demeter | PP-46, CC-80 |

### Component Principles (for packages/modules)
| Acronym | Meaning | Rule |
|---------|---------|------|
| REP | Reuse/Release Equivalence | CA-14 |
| CCP | Common Closure Principle | CA-15 |
| CRP | Common Reuse Principle | CA-16 |
| ADP | Acyclic Dependencies Principle | CA-18 |
| SDP | Stable Dependencies Principle | CA-19 |
| SAP | Stable Abstractions Principle | CA-20 |

**For full glossary:** See [principles-glossary.md](references/principles-glossary.md)

**For DRY vs WET guidance:** See [principles-spectrum.md](references/principles-spectrum.md)

---

## Common Mistakes to Avoid
| Mistake | Why It's Wrong | Correct Approach |
|---------|----------------|------------------|
| Skipping positioning | Wrong strictness applied | Always ask Q1/Q2/Q3 first |
| Treating metrics as gates | Clear 60-line fn > confusing 20-line ones | Metrics trigger discussion |
| Ignoring language paradigm | OOP rules ≠ Rust/Go | Check language-adjustments first |
| Reviewing formatting | Linters do this better | Focus on logic and design |
| Citing rules without context | "CC-20" alone doesn't help | Add: "Function too long: consider extracting X" |
| Missing forest for trees | All style issues but miss security | Priority: security > correctness > design > style |

---

## The Bottom Line
1. **Always calibrate:** Project level determines strictness
2. **Cite rules:** Every issue references a rule code
3. **Focus on logic:** Let machines handle formatting
4. **Be pragmatic:** Rules serve the code, not vice versa
