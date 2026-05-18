# Component Guidelines

`skill-miniapp` components are thin React function components with the data contract defined first, followed by rendering logic. The important patterns in this subsystem are part delegation, collapsible diagnostic blocks, and status-aware cards.

## Prop-Interface-First Style

Declare the prop interface immediately above the component and keep the file export explicit.

From `skill-miniapp/src/components/MessageBubble.tsx`:

```tsx
interface MessageBubbleProps {
  message: Message;
  onQuestionAnswer?: (answer: string, toolCallId?: string) => void;
  onPermissionDecision?: (
    permissionId: string,
    response: 'once' | 'always' | 'reject',
    subagentSessionId?: string,
  ) => void;
}

export const MessageBubble: React.FC<MessageBubbleProps> = ({
  message,
  onQuestionAnswer,
  onPermissionDecision,
}) => {
```

Use the same shape for leaf renderers and action widgets. `ThinkingBlock`, `ToolCard`, `QuestionCard`, `PermissionCard`, and `SendToImButton` all keep their props local to the file.

## Pattern: Part-Renderer Delegation

`MessageBubble` owns the switch over `MessagePart.type`, but each branch delegates to a focused renderer. This is the main extension seam for assistant output.

From `skill-miniapp/src/components/MessageBubble.tsx`:

```tsx
const renderPart = (part: MessagePart) => {
  switch (part.type) {
    case 'thinking':
      return <ThinkingBlock key={part.partId} part={part} />;

    case 'tool':
      return <ToolCard key={part.partId} part={part} />;

    case 'question':
      return (
        <QuestionCard
          key={part.partId}
          part={part}
          onAnswer={onQuestionAnswer}
        />
      );

    case 'permission':
      return (
        <PermissionCard
          key={part.partId}
          part={part}
          onDecision={onPermissionDecision}
        />
      );

    case 'subtask':
      return (
        <SubtaskBlock
          key={part.partId}
          part={part}
          onPermissionDecision={onPermissionDecision}
          onQuestionAnswer={onQuestionAnswer}
        />
      );
```

Rules:
- Add new assistant part types by extending this switch and the protocol layer together.
- Keep the renderer branch small. If a branch needs internal state, move it into a dedicated component.
- Use `part.partId` as the key. Do not fall back to array index keys.

## Pattern: Markdown And Code Fences

Markdown rendering stays in `MessageBubble`; fenced code is upgraded through `CodeBlock`.

From `skill-miniapp/src/components/MessageBubble.tsx`:

```tsx
const markdownComponents: Components = useMemo(
  () => ({
    code({ className, children, ...rest }) {
      const match = /language-(\w+)/.exec(className ?? '');
      const codeString = String(children).replace(/\n$/, '');
      if (match) {
        return <CodeBlock code={codeString} language={match[1]} />;
      }
      return (
        <code className={className} {...rest}>
          {children}
        </code>
      );
    },
  }),
  [],
);
```

From `skill-miniapp/src/components/CodeBlock.tsx`:

```tsx
let highlighterPromise: Promise<Highlighter> | null = null;

function getOrCreateHighlighter(): Promise<Highlighter> {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighter({
      themes: ['catppuccin-mocha'],
      langs: [
        'javascript',
        'typescript',
        'json',
        'html',
        'css',
        'python',
        'java',
        'go',
        'rust',
        'bash',
        'sql',
        'yaml',
        'markdown',
        'tsx',
        'jsx',
      ],
    });
  }
  return highlighterPromise;
}
```

This means new message renderers should not re-implement markdown or highlighting. Reuse `MessageBubble` plus `CodeBlock`.

## Pattern: Collapsible Thinking Blocks

`ThinkingBlock` is the reference pattern for "summary row plus expandable body" rendering.

From `skill-miniapp/src/components/ThinkingBlock.tsx`:

```tsx
export const ThinkingBlock: React.FC<ThinkingBlockProps> = ({ part }) => {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className={`thinking-block ${part.isStreaming ? 'streaming' : ''}`}>
      <div
        className="thinking-block__header"
        onClick={() => setExpanded(!expanded)}
        role="button"
        tabIndex={0}
      >
```

And the body stays completely conditional:

From `skill-miniapp/src/components/ThinkingBlock.tsx`:

```tsx
{expanded && (
  <div className="thinking-block__content">
    <ReactMarkdown remarkPlugins={[remarkGfm]}>
      {part.content}
    </ReactMarkdown>
    {part.isStreaming && <span className="streaming-cursor" />}
  </div>
)}
```

Use this pattern for long diagnostic content. Keep the collapsed header cheap and defer heavy markdown rendering to the expanded branch.

## Pattern: Tool Card State Machine

`ToolCard` derives UI state directly from `part.toolStatus` and encodes that state into BEM-style classes.

From `skill-miniapp/src/components/ToolCard.tsx`:

```tsx
export const ToolCard: React.FC<ToolCardProps> = ({ part }) => {
  const [expanded, setExpanded] = useState(false);
  const status = part.toolStatus ?? 'pending';
  const statusLabel = statusLabels[status] ?? status;

  return (
    <div className={`tool-card tool-card--${status}`}>
      <div
        className="tool-card__header"
        onClick={() => setExpanded(!expanded)}
        role="button"
        tabIndex={0}
      >
```

The body only shows the sections that exist on the part.

From `skill-miniapp/src/components/ToolCard.tsx`:

```tsx
{expanded && (
  <div className="tool-card__body">
    {part.toolInput && (
      <div className="tool-card__section">
        <pre className="tool-card__code">
          {JSON.stringify(part.toolInput, null, 2)}
        </pre>
      </div>
    )}
    {part.toolOutput && (
      <div className="tool-card__section">
        <pre className="tool-card__code">{part.toolOutput}</pre>
      </div>
    )}
    {status === 'error' && part.content && (
      <div className="tool-card__section tool-card__error">
        <pre className="tool-card__code">{part.content}</pre>
      </div>
    )}
  </div>
)}
```

Keep the state source of truth on the `MessagePart`; do not duplicate `pending/running/completed/error` in component-local state.

## Pattern: Optimistic Interactive Cards (PermissionCard, QuestionCard)

Interactive cards keep local `resolved` / `answered` state so the click is instant, then sync it back from `part.permResolved` (or `part.answered`) via a `useEffect` so the hook-side truth eventually wins.

From `skill-miniapp/src/components/PermissionCard.tsx`:

```tsx
const [resolved, setResolved] = useState(part.permResolved ?? false);

useEffect(() => {
  setResolved(part.permResolved ?? false);
}, [part.permResolved]);

const handleDecision = (response: 'once' | 'always' | 'reject') => {
  if (resolved) {
    return;
  }
  setResolved(true);
  if (part.permissionId) {
    onDecision?.(part.permissionId, response, part.subagentSessionId);
  }
};
```

That is why `useSkillStream.replyPermission` must immediately patch `permResolved` on both main message parts and `subParts` (see `hook-guidelines.md`): if the hook does not, the card's `useEffect` will reset `resolved` back to `false` on the next render.

Rule: never couple a "one-time action" card's local state directly to the REST response. Keep it driven by the part field so the protocol layer remains the source of truth.

## Pattern: SubtaskBlock Delegates Back To The Same Renderers

`SubtaskBlock` does not reinvent subagent rendering; it owns the collapsed/expanded state, the pending-interaction auto-expand, and delegates each `subPart` back to `ToolCard`, `ThinkingBlock`, `PermissionCard`, and `QuestionCard`.

From `skill-miniapp/src/components/SubtaskBlock.tsx`:

```tsx
// 有 pending permission/question 时自动展开
const hasPendingInteraction = useMemo(
  () => subParts.some(
    (p) => (p.type === 'permission' && !p.permResolved) || (p.type === 'question' && !p.answered),
  ),
  [subParts],
);

useEffect(() => {
  if (hasPendingInteraction) {
    setCollapsed(false);
  }
}, [hasPendingInteraction]);
```

The inner switch mirrors `MessageBubble` but with a narrower supported set.

From `skill-miniapp/src/components/SubtaskBlock.tsx`:

```tsx
switch (subPart.type) {
  case 'text':
    return <div key={key} className="subtask-block__text">{subPart.content}</div>;
  case 'thinking':
    return <ThinkingBlock key={key} part={subPart} />;
  case 'tool':
    return <ToolCard key={key} part={subPart} />;
  case 'permission':
    return <PermissionCard key={key} part={subPart} onDecision={onPermissionDecision} />;
  case 'question':
    return <QuestionCard key={key} part={subPart} onAnswer={onQuestionAnswer} />;
  default:
    return null;
}
```

Rules for new subagent parts:
- Teach `handleSubagentMessage` in `useSkillStream.ts` how to upsert that part shape.
- Extend the `SubtaskBlock` inner switch so the new kind has a visual representation.
- Do not add a top-level renderer in `MessageBubble` if the part only makes sense nested under a subagent.

## Styling Reality In This Subsystem

- `app.tsx` plus most conversation components rely on `skill-miniapp/src/index.css`.
- `SkillMain.tsx`, `CodeBlock.tsx`, and `SendToImButton.tsx` intentionally keep local `styles` maps because they are embedded or utility surfaces.
- When touching an existing inline-style component, extend the existing `styles` object instead of scattering anonymous inline objects across JSX.

## Extension Checklist

When you introduce a new assistant part:
- Add the `type` to `skill-miniapp/src/protocol/types.ts`.
- Teach `skill-miniapp/src/protocol/StreamAssembler.ts` how to build that part.
- Teach `skill-miniapp/src/protocol/history.ts` how to normalize historical payloads for that part.
- Add the renderer branch to `skill-miniapp/src/components/MessageBubble.tsx`.
- If the part can appear inside subagent output, make sure `SubtaskBlock.tsx` can render it too.
