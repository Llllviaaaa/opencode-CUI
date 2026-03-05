/**
 * EventFilter Unit Tests
 *
 * Tests the shouldRelay() function against all known OpenCode event types.
 */

import { describe, test, expect } from 'bun:test';
import { shouldRelay } from '../EventFilter';

describe('EventFilter.shouldRelay', () => {
    // -----------------------------------------------------------------------
    // Events that SHOULD be relayed (conversation-related)
    // -----------------------------------------------------------------------

    describe('relay events (should return true)', () => {
        const relayEvents = [
            // message.* prefix
            'message.created',
            'message.updated',
            'message.completed',
            'message.part.updated',

            // permission.* prefix
            'permission.updated',
            'permission.replied',

            // session.* prefix
            'session.created',
            'session.updated',
            'session.deleted',

            // Exact matches
            'file.edited',
            'todo.updated',
            'command.executed',
        ];

        for (const eventType of relayEvents) {
            test(`should relay: ${eventType}`, () => {
                expect(shouldRelay(eventType)).toBe(true);
            });
        }
    });

    // -----------------------------------------------------------------------
    // Events that should NOT be relayed (local/TUI)
    // -----------------------------------------------------------------------

    describe('local events (should return false)', () => {
        const localEvents = [
            // TUI events
            'tui.prompt.append',
            'tui.prompt.replace',

            // PTY events
            'pty.created',
            'pty.output',
            'pty.terminated',

            // File watcher events (NOT file.edited)
            'file.watcher.discovered',
            'file.watcher.changed',

            // Server events
            'server.heartbeat',
            'server.connected',

            // Other local events
            'config.updated',
            'project.updated',
            'find.result',
            'lsp.diagnostics',
            'formatter.result',

            // Unknown / future events
            'some.unknown.event',
            'another.random.type',
        ];

        for (const eventType of localEvents) {
            test(`should NOT relay: ${eventType}`, () => {
                expect(shouldRelay(eventType)).toBe(false);
            });
        }
    });

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    describe('edge cases', () => {
        test('empty string should not relay', () => {
            expect(shouldRelay('')).toBe(false);
        });

        test('partial prefix match: "mess" should not relay', () => {
            expect(shouldRelay('mess')).toBe(false);
        });

        test('exact prefix without suffix: "message." should relay', () => {
            // "message." starts with "message." so it matches
            expect(shouldRelay('message.')).toBe(true);
        });

        test('case sensitivity: "Message.created" should not relay', () => {
            // JavaScript string comparison is case-sensitive
            expect(shouldRelay('Message.created')).toBe(false);
        });
    });
});
