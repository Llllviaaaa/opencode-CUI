import React, { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { Components } from 'react-markdown';
import { CodeBlock } from './CodeBlock';
import type { Message } from '../protocol/types';

interface MessageBubbleProps {
  message: Message;
}

const roleLabels: Record<string, string> = {
  user: '你',
  assistant: 'OpenCode',
  system: '系统',
  tool: '工具',
};

export const MessageBubble: React.FC<MessageBubbleProps> = ({ message }) => {
  const isUser = message.role === 'user';
  const roleClass = message.role;

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

  const renderContent = () => {
    if (message.role === 'assistant' || message.role === 'tool') {
      return (
        <>
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
            {message.content}
          </ReactMarkdown>
          {message.isStreaming && <span className="streaming-cursor" />}
        </>
      );
    }
    return <span style={{ whiteSpace: 'pre-wrap' }}>{message.content}</span>;
  };

  return (
    <div className={`message-wrapper ${isUser ? 'user' : 'other'}`}>
      <div className={`message-bubble ${roleClass}`}>
        {!isUser && (
          <div className="message-role-label">
            {roleLabels[message.role] ?? message.role}
          </div>
        )}
        {renderContent()}
      </div>
    </div>
  );
};
