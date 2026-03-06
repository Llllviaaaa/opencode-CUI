import React, { useState, useCallback, useRef } from 'react';
import { SessionSidebar } from './components/SessionSidebar';
import { ConversationView } from './components/ConversationView';
import { MessageInput } from './components/MessageInput';
import { useSkillSession } from './hooks/useSkillSession';
import { useSkillStream } from './hooks/useSkillStream';
import './index.css';

const SKILL_DEFINITION_ID = 1;
const DEFAULT_USER_ID = '1'; // Test user

const agentStatusConfig: Record<string, { className: string; label: string }> = {
  online: { className: 'online', label: 'Online' },
  offline: { className: 'offline', label: 'Offline' },
  unknown: { className: 'unknown', label: 'Connecting...' },
};

const App: React.FC = () => {
  const [sidebarVisible, setSidebarVisible] = useState(true);
  const conversationContainerRef = useRef<HTMLDivElement | null>(null);

  const {
    sessions,
    currentSession,
    loading: sessionsLoading,
    error: sessionError,
    createSession,
    switchSession,
  } = useSkillSession(DEFAULT_USER_ID);

  const activeSessionId = currentSession?.id ?? null;

  const {
    messages,
    isStreaming,
    agentStatus,
    sendMessage,
    error: streamError,
  } = useSkillStream(activeSessionId);

  const handleNewSession = useCallback(async () => {
    await createSession({
      skillDefinitionId: SKILL_DEFINITION_ID,
      userId: 1,
      title: `Session ${new Date().toLocaleString()}`,
    });
  }, [createSession]);

  const handleSendMessage = useCallback(
    async (text: string) => {
      if (!activeSessionId) {
        const session = await createSession({
          skillDefinitionId: SKILL_DEFINITION_ID,
          userId: 1,
          title: text.slice(0, 50),
        });
        if (session) {
          setTimeout(() => void sendMessage(text), 100);
        }
        return;
      }
      await sendMessage(text);
    },
    [activeSessionId, createSession, sendMessage],
  );

  const displayError = sessionError ?? streamError;
  const statusCfg = agentStatusConfig[agentStatus] ?? agentStatusConfig.unknown;

  return (
    <div className="app-layout">
      {/* ---- Top Bar ---- */}
      <div className="app-topbar">
        <span className="app-title">💬 OpenCode Chat</span>
        <span className="app-badge">v1</span>
        <span className="spacer" />
        <span className={`status-indicator ${statusCfg.className}`} />
        <span className="status-label">{statusCfg.label}</span>
        <button
          type="button"
          className="btn btn-sidebar"
          onClick={() => setSidebarVisible((v) => !v)}
        >
          {sidebarVisible ? '隐藏侧栏' : '显示侧栏'}
        </button>
      </div>

      {/* ---- Error Banner ---- */}
      {displayError && <div className="error-banner">{displayError}</div>}

      {/* ---- Body ---- */}
      <div className="app-body">
        {sidebarVisible && (
          <SessionSidebar
            sessions={sessions}
            activeSessionId={activeSessionId}
            onSelect={(id) => switchSession(id)}
            onNewSession={handleNewSession}
          />
        )}
        <div className="main-content">
          <div ref={conversationContainerRef} style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            <ConversationView messages={messages} loading={sessionsLoading} />
          </div>
          <MessageInput
            onSend={handleSendMessage}
            disabled={isStreaming}
            placeholder={
              activeSessionId
                ? '输入消息... (Enter 发送, Shift+Enter 换行)'
                : '输入消息开始新会话...'
            }
          />
        </div>
      </div>
    </div>
  );
};

export default App;
