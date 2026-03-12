# Project Positioning System

A systematic approach to determine the appropriate strictness level for code review.

## Table of Contents
- [Quick Assessment Flow](#quick-assessment-flow)
- [Question Definitions](#question-definitions)
- [Complete Mapping Table](#complete-mapping-table)
- [Level Definitions](#level-definitions)
- [Strictness Matrix](#strictness-matrix)
- [Metric Thresholds](#metric-thresholds)

---

## Quick Assessment Flow
```
                    START
                      │
                      ▼
        ┌─────────────────────────┐
        │ Q1: Who uses this code? │
        └─────────────────────────┘
                      │
         ┌────────────┼────────────┐
         ▼            ▼            ▼
        D1           D2           D3
       Solo       Internal     External
         │            │            │
         ▼            ▼            ▼
        ┌─────────────────────────┐
        │ Q2: What standard?      │
        └─────────────────────────┘
                      │
      ┌───────┬───────┼───────┬───────┐
      ▼       ▼       ▼       ▼       │
     R1      R2      R3      R4       │
    Ship   Normal  Careful  Strict    │
      │       │       │       │       │
      │       │       ▼       ▼       │
      │       │   ┌───────────────┐   │
      │       │   │ Q3: Critical? │◄──┘
      │       │   │ (D2/D3 only)  │
      │       │   └───────────────┘
      │       │         │
      │       │    ┌────┴────┐
      │       │    ▼         ▼
      │       │   C1        C2
      │       │  Normal   Critical
      │       │    │         │
      ▼       ▼    ▼         ▼
        ┌─────────────────────────┐
        │    LOOKUP TABLE         │
        │    → L1, L2, L3, L4, L5 │
        └─────────────────────────┘
```

---

## Question Definitions

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

### Q3: How critical is this code? (Conditional)
> **Only ask if:** (D2 or D3) AND (R3 or R4)

| Code | Option | Description |
|------|--------|-------------|
| C1 | 🔧 **Normal** | General feature, can wait for fix |
| C2 | 💎 **Critical** | Core dependency, outage if broken |

---

## Complete Mapping Table

### D1: Solo (No Q3 needed)
| D | R | C | Level | Example |
|---|---|---|-------|---------|
| D1 | R1 | - | L1 | Experiment script |
| D1 | R2 | - | L1 | Personal utility |
| D1 | R3 | - | L2 | Personal long-term project |
| D1 | R4 | - | L3 | Personal perfectionist |

### D2: Internal
| D | R | C | Level | Example |
|---|---|---|-------|---------|
| D2 | R1 | - | L1 | Team prototype |
| D2 | R2 | - | L2 | Team daily dev |
| D2 | R3 | C1 | L2 | Internal helper tool |
| D2 | R3 | C2 | L3 | Internal SDK |
| D2 | R4 | C1 | L3 | Internal tool (high std) |
| D2 | R4 | C2 | L4 | Internal core infra |

### D3: External
| D | R | C | Level | Example |
|---|---|---|-------|---------|
| D3 | R1 | - | L2 | Product MVP |
| D3 | R2 | - | L3 | General product feature |
| D3 | R3 | C1 | L3 | Small OSS tool |
| D3 | R3 | C2 | L4 | Product core feature |
| D3 | R4 | C1 | L4 | OSS tool (high std) |
| D3 | R4 | C2 | L5 | Finance/Medical/Core OSS |

---

## Level Definitions
| Level | Name | Key Question | Typical Projects |
|-------|------|--------------|------------------|
| **L1** | 🧪 Lab | Does it run? | Experiments, throwaway scripts |
| **L2** | 🛠️ Tool | Can I understand it next month? | Personal tools, team prototypes |
| **L3** | 🤝 Team | Can teammates take over? | Team projects, small OSS |
| **L4** | 🚀 Infra | Will others suffer if I break it? | Internal SDK, core services, popular OSS |
| **L5** | 🏛️ Critical | Can it pass audit? | Finance, medical, critical infrastructure |

### Level Characteristics
| Level | API Stability | Backward Compat | Documentation | Review Required |
|-------|---------------|-----------------|---------------|-----------------|
| L1 | None | None | None | Optional |
| L2 | Informal | None | Minimal | Self |
| L3 | Documented | Best effort | README + comments | 1 reviewer |
| L4 | Semver | Migration path | Full API docs | 2+ reviewers |
| L5 | Strict semver | Mandatory | Complete + audit trail | Team + security |

---

## Strictness Matrix
| Check Item | L1 | L2 | L3 | L4 | L5 |
|------------|----|----|----|----|-----|
| Functional correctness | ★★★ | ★★★★ | ★★★★★ | ★★★★★ | ★★★★★ |
| Error handling | ★ | ★★ | ★★★ | ★★★★ | ★★★★★ |
| Naming & readability | ★ | ★★★ | ★★★★ | ★★★★★ | ★★★★★ |
| Architecture design | ☆ | ★ | ★★★ | ★★★★★ | ★★★★★ |
| Test coverage | ☆ | ★ | ★★★ | ★★★★ | ★★★★★ |
| API stability | ☆ | ☆ | ★★ | ★★★★★ | ★★★★★ |
| Backward compatibility | ☆ | ☆ | ★ | ★★★★★ | ★★★★★ |
| Documentation | ☆ | ★ | ★★ | ★★★★ | ★★★★★ |
| Security | ☆ | ★ | ★★ | ★★★ | ★★★★★ |

---

## Metric Thresholds

### Code Metrics
| Metric | L1 | L2 | L3 | L4 | L5 |
|--------|-----|-----|-----|-----|-----|
| Function length | N/A | ≤80 | ≤50 | ≤30 | ≤20 |
| Parameter count | N/A | ≤7 | ≤5 | ≤3 | ≤2 |
| Nesting depth | N/A | ≤5 | ≤4 | ≤3 | ≤2 |
| PR size (lines) | N/A | ≤800 | ≤500 | ≤300 | ≤200 |
| Test coverage | N/A | 30% | 60% | 80% | 95% |
| DRY tolerance (max repeats) | N/A | 4 | 3 | 2 | 1 |

### Quality Gates
| Gate | L1 | L2 | L3 | L4 | L5 |
|------|----|----|----|----|-----|
| Linter pass | Optional | Required | Required | Required | Required |
| Type check | Optional | Optional | Required | Required | Required |
| Unit tests | None | Some | Core paths | Comprehensive | Complete |
| Integration tests | None | None | Optional | Required | Required |
| Security scan | None | None | Optional | Required | Required + audit |
| Code review | None | Self | 1 person | 2+ people | Team + security |

---

## Statistics
| Item | Value |
|------|-------|
| Total options | 3 + 4 + 2 = 9 |
| Valid combinations | 20 |
| Average questions | 2.3 |
| Output levels | L1-L5 (5 levels) |
