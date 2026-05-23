# Resolve PR 56 Merge Conflicts

## Goal
Resolve merge conflicts on PR #56 (`codex/assistant-instance-info-cache` -> `main`) without losing the assistant instance routing, no-AK remote assistant, controller flow-service, and default-assistant fixes already present on the branch.

## Requirements
- Merge or otherwise integrate latest `origin/main` into `codex/assistant-instance-info-cache`.
- Resolve conflicts in skill-server controller/service/test files and Trellis specs.
- Preserve the final routing rules:
  - no AK alone does not imply remote assistant;
  - remote assistant means `isRemote=true` or `remoteProperty` is non-empty;
  - local no-AK assistant remains `UNKNOWN`;
  - controller remains lightweight and delegates orchestration to flow services.
- Run relevant Maven tests after resolution.
- Push the resolved branch so PR #56 becomes mergeable.

## Acceptance Criteria
- [ ] `git status` is clean after commit.
- [ ] PR #56 no longer reports `mergeable=CONFLICTING`.
- [ ] `skill-server` tests pass for the affected scope or full module if practical.
- [ ] `ai-gateway` tests remain green if touched by merge resolution.
- [ ] Conflict resolution commit is pushed to `origin/codex/assistant-instance-info-cache`.

## Technical Notes
- Prefer a merge commit from `origin/main` to avoid rewriting the already-open PR branch.
- Do not revert unrelated main changes; integrate them with the branch behavior.
