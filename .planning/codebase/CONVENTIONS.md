# Code Conventions

## General Style

The repository uses conventional naming and separation patterns rather than an unusual custom framework.

Observed conventions:
- Java package names follow `com.opencode.cui.<module>`
- Spring stereotypes are used consistently: `@Service`, `@Component`, `@RestController`, `@Configuration`
- MyBatis mapper XML lives under `src/main/resources/mapper/`
- React files use PascalCase for components and camelCase for hooks/utilities
- TypeScript plugin classes are explicit and interface-driven

## Java Backend Conventions

Examples from `ai-gateway/` and `skill-server/`:
- controller classes end with `Controller`
- service classes end with `Service`
- repository interfaces end with `Repository`
- WebSocket transport classes live in `ws/`
- configuration properties classes end with `Properties`
- models include both entity-like classes and request/response DTOs

Error-handling related conventions:
- global exception handling is centralized in `skill-server/src/main/java/com/opencode/cui/skill/config/GlobalExceptionHandler.java`
- protocol-specific helpers exist, such as `ProtocolException.java` and `ProtocolUtils.java`
- plugin code has dedicated error mapping in `plugins/message-bridge/src/error/` and `plugins/message-bridge/src/utils/error.ts`

## Frontend Conventions

Observed patterns in `skill-miniapp/src/`:
- custom hooks encapsulate remote data and streaming state
- protocol normalization is isolated from rendering code
- UI components receive typed props and remain comparatively presentation-focused
- API calls are centralized in `skill-miniapp/src/utils/api.ts`

Examples:
- `useSkillSession.ts` owns CRUD-oriented session state
- `useSkillStream.ts` owns socket lifecycle, resume, merge, and stream normalization
- `ConversationView.tsx` and `MessageBubble.tsx` render message structures rather than fetch data directly

## Plugin Conventions

The plugin is the most explicit about design rules.

Observed norms:
- contracts first, protocol second, runtime/action later
- separate normalizers and extractors for inbound vs outbound message shapes
- action classes are named after supported commands
- runtime helpers prefer typed adapters over implicit object access
- logs and errors are normalized before being emitted

Useful examples:
- `plugins/message-bridge/src/action/ChatAction.ts`
- `plugins/message-bridge/src/action/ActionRegistry.ts`
- `plugins/message-bridge/src/runtime/SdkAdapter.ts`
- `plugins/message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts`

## Testing Conventions

- Java tests use JUnit `@Test`
- plugin tests use `bun test`
- top-level Python tests use `pytest` and `pytest-asyncio`
- test names encode scenario IDs such as `test_u45_...`, `test_int06_...`, `test_perf03_...`

This suggests traceability to a separate requirements or test-case catalog.

## Naming And API Shape Conventions

- session and message concepts consistently use `Session`, `Message`, and `Part`
- transport-specific payloads are usually typed and explicitly named
- status concepts use small controlled vocabularies such as `online`, `offline`, `unknown`

## Convention Drift / Smells

- some checked-in files show mojibake or encoding corruption in comments and UI strings
- frontend code uses `useCallback` heavily; without a documented rule set, this may be more habitual than necessary
- there is no single visible linting config at repository root, so enforcement may differ per subproject
