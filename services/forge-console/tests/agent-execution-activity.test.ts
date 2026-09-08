import { describe, expect, it } from 'vitest';
import {
  captureStatusPresentation,
  latestTokenUsage,
  renderAgentExecutionActivityEvent,
  renderAgentExecutionActivityEvents
} from '../src/operator/agent-execution-activity.js';
import type { AgentExecutionEvent } from '../src/operator/agent-projects-api.js';

function event(
  sequence: number,
  type: string,
  payload: unknown,
  overrides: Partial<AgentExecutionEvent> = {}
): AgentExecutionEvent {
  return {
    id: `event-${sequence}`,
    agentSessionId: 'session-1',
    agentTurnId: 'turn-1',
    nodeRunId: null,
    sequence,
    type,
    status: null,
    phase: null,
    providerEventKey: null,
    payload,
    occurredAt: `2026-09-08T12:00:${String(sequence).padStart(2, '0')}.000Z`,
    createdAt: `2026-09-08T12:00:${String(sequence).padStart(2, '0')}.000Z`,
    ...overrides
  };
}

describe('agent execution activity presentation', () => {
  it('maps capture statuses to user-facing labels and tones', () => {
    expect(captureStatusPresentation('NOT_STARTED')).toEqual({ label: 'Waiting', tone: 'waiting' });
    expect(captureStatusPresentation('ACTIVE')).toEqual({ label: 'Live', tone: 'live' });
    expect(captureStatusPresentation('COMPLETE')).toEqual({ label: 'Complete', tone: 'complete' });
    expect(captureStatusPresentation('DEGRADED')).toEqual({ label: 'Incomplete', tone: 'incomplete' });
    expect(captureStatusPresentation('UNAVAILABLE')).toEqual({ label: 'Unavailable', tone: 'unavailable' });
  });

  it('uses only the latest token usage and omits absent values', () => {
    const usage = latestTokenUsage([
      event(1, 'TOKEN_USAGE', { total: { input: 12000, cached: 8100 } }),
      event(2, 'TOKEN_USAGE', { total: { input: 12400, output: 2300 }, modelContextWindow: 200000 })
    ]);

    expect(usage).toEqual(['Input 12.4k', 'Output 2.3k', 'Context 200k']);
    expect(latestTokenUsage([
      event(3, 'TOKEN_USAGE', { total: { input: null, output: '' }, modelContextWindow: undefined })
    ])).toEqual([]);
  });

  it('selects token usage by greatest sequence despite shuffled input and opposing timestamps', () => {
    const usage = latestTokenUsage([
      event(9, 'TOKEN_USAGE', { total: { input: 9000 } }, { occurredAt: '2026-09-08T10:00:00.000Z' }),
      event(2, 'TOKEN_USAGE', { total: { input: 2000 } }, { occurredAt: '2026-09-08T12:00:00.000Z' }),
      event(12, 'COMMAND', { command: 'ls' }),
      event(5, 'TOKEN_USAGE', { total: { input: 5000 } }, { occurredAt: '2026-09-08T11:00:00.000Z' })
    ]);

    expect(usage).toEqual(['Input 9k']);
  });

  it('renders sequence ASC despite shuffled input and opposing timestamps', () => {
    const html = renderAgentExecutionActivityEvents([
      event(9, 'COMMAND', { command: 'last' }, { occurredAt: '2026-09-08T10:00:00.000Z' }),
      event(2, 'PLAN', { explanation: 'first' }, { occurredAt: '2026-09-08T12:00:00.000Z' }),
      event(5, 'REASONING_SUMMARY', { summary: 'middle' }, { occurredAt: '2026-09-08T11:00:00.000Z' })
    ]);

    expect([...html.matchAll(/data-sequence="(\d+)"/g)].map((match) => match[1])).toEqual(['2', '5', '9']);
  });

  it.each([
    ['PLAN', 'Plan'],
    ['REASONING_SUMMARY', 'Reasoning summary'],
    ['COMMAND', 'Command'],
    ['FILE_CHANGE', 'File change'],
    ['TOOL_CALL', 'Tool call']
  ])('renders retained %s content safely with its title, status, and truncation', (type, title) => {
    const html = renderAgentExecutionActivityEvent(event(1, type, {
      contentSummary: '<script>alert("retained")</script>\n{"command":"ls","steps":[{"step":"Inspect"}]}',
      truncated: true,
      originalBytes: 140000,
      storedBytes: 120000,
      requestBody: 'private request body',
      hiddenReasoning: 'private reasoning'
    }, { status: 'SUCCEEDED' }));

    expect(html).toContain('<pre>&lt;script&gt;alert(&quot;retained&quot;)&lt;/script&gt;\n{&quot;command&quot;:&quot;ls&quot;,&quot;steps&quot;:[{&quot;step&quot;:&quot;Inspect&quot;}]}</pre>');
    expect(html).toContain(`<strong>${title}</strong>`);
    expect(html).toContain('>SUCCEEDED</span>');
    expect(html).toContain('Content truncated (120000 of 140000 bytes shown)');
    expect(html.match(/class="agent-activity-truncated"/g)).toHaveLength(1);
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('class="agent-activity-command"');
    expect(html).not.toContain('class="agent-activity-plan"');
    expect(html).not.toContain('requestBody');
    expect(html).not.toContain('private request body');
    expect(html).not.toContain('private reasoning');
  });

  it('renders every Forge activity event through provider-neutral semantic markup', () => {
    const cases = [
      event(1, 'TURN', {}, { status: 'STARTED' }),
      event(2, 'PLAN', { explanation: 'Smallest safe change', steps: [{ step: 'Inspect', status: 'completed' }, { step: 'Patch', status: 'inProgress' }, { step: 'Test', status: 'pending' }, { step: 'Review', status: 'blocked' }] }),
      event(3, 'REASONING_SUMMARY', { summary: ['Visible summary', { decision: 'additive' }] }),
      event(4, 'COMMAND', { command: 'printf "a\\n b"', cwd: '/workspace', output: 'a\n b', exitCode: 1, durationMs: 12700, truncated: true, originalBytes: 100, storedBytes: 20 }, { status: 'FAILED' }),
      event(5, 'FILE_CHANGE', { changes: [{ path: 'src/App.java', operation: 'UPDATE', summary: 'Mapper' }], paths: ['src/Test.java'] }),
      event(6, 'TOOL_CALL', { toolKind: 'MCP', tool: 'search', server: 'drive', operation: 'files.search', providerStatus: 'completed', responseSummary: { result: 'found' }, requestBody: 'must-not-render' }, { status: 'SUCCEEDED' }),
      event(7, 'AGENT_MESSAGE', { message: 'Implementation complete.' }, { phase: 'FINAL' }),
      event(8, 'WARNING', { message: 'Context was compacted.' }),
      event(9, 'ERROR', { message: 'Provider returned an execution error.' }),
      event(10, 'TOKEN_USAGE', { total: { input: 2 } }),
      event(11, 'CONTEXT_COMPACTION', { status: 'completed' }),
      event(12, 'FUTURE_EVENT', { value: '<safe>' })
    ];

    const rendered = cases.map(renderAgentExecutionActivityEvent);

    expect(rendered[0]).toContain('Turn');
    expect(rendered[0]).toContain('STARTED');

    expect(rendered[1]).toContain('Plan');
    expect(rendered[1]).toContain('Smallest safe change');
    expect(rendered[1]).toContain('✓');
    expect(rendered[1]).toContain('•');
    expect(rendered[1]).toContain('○');
    expect(rendered[1]).toContain('blocked');

    expect(rendered[2]).toContain('Reasoning summary');
    expect(rendered[2]).toContain('Visible summary');
    expect(rendered[2]).toContain('additive');

    expect(rendered[3]).toContain('Command');
    expect(rendered[3]).toContain('FAILED');
    expect(rendered[3]).not.toContain('Invocation failed');
    expect(rendered[3]).toContain('<details');
    expect(rendered[3]).toContain('<summary>Output</summary>');
    expect(rendered[3]).toContain('Output truncated');
    expect(rendered[3]).toContain('12.7s');

    expect(rendered[4]).toContain('File change');
    expect(rendered[4]).toContain('UPDATE');
    expect(rendered[4]).toContain('src/App.java');
    expect(rendered[4]).toContain('src/Test.java');

    expect(rendered[5]).toContain('Tool call');
    expect(rendered[5]).toContain('MCP');
    expect(rendered[5]).toContain('drive');
    expect(rendered[5]).toContain('files.search');
    expect(rendered[5]).toContain('<details');
    expect(rendered[5]).toContain('<summary>Response</summary>');
    expect(rendered[5]).toContain('found');
    expect(rendered[5]).not.toContain('requestBody');
    expect(rendered[5]).not.toContain('must-not-render');

    expect(rendered[6]).toContain('Agent message');
    expect(rendered[6]).toContain('Implementation complete.');
    expect(rendered[6]).toContain('agent-activity-event-final');
    expect(renderAgentExecutionActivityEvent(
      event(13, 'AGENT_MESSAGE', { text: 'Legacy message.' }, { phase: 'FINAL' })
    )).toContain('Legacy message.');

    expect(rendered[7]).toContain('Warning');
    expect(rendered[7]).toContain('Context was compacted.');
    expect(rendered[8]).toContain('Error');
    expect(rendered[8]).toContain('Provider returned an execution error.');
    expect(rendered[9]).toBe('');
    expect(rendered[10]).toContain('Context compaction');
    expect(rendered[10]).toContain('completed');
    expect(rendered[11]).toContain('Future event');
    expect(rendered[11]).toContain('&lt;safe&gt;');

    const list = renderAgentExecutionActivityEvents(cases);
    expect(list).not.toContain('Input 2');
    expect(list.match(/<li class="agent-activity-event/g)).toHaveLength(11);
  });

  it('escapes event content and exposes summaries without hidden reasoning', () => {
    const html = renderAgentExecutionActivityEvent(event(1, 'REASONING_SUMMARY', {
      summary: ['<script>alert(1)</script>'],
      chainOfThought: 'Chain of thought',
      hiddenReasoning: 'Hidden reasoning'
    }));
    const commandHtml = renderAgentExecutionActivityEvent(event(2, 'COMMAND', {
      command: 'printf "a\\n b"',
      output: 'a\n b'
    }));

    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('Chain of thought');
    expect(html).not.toContain('Hidden reasoning');
    expect(commandHtml).toContain('<pre');
    expect(commandHtml).toContain('a\n b');
  });
});
