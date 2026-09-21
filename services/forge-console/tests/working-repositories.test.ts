import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import { describe, it, expect, vi } from 'vitest';
import { TaskExecutionView } from '../src/operator/task-execution-view.js';
import { WorkflowBuilder } from '../src/operator/workflow-builder.js';

async function setup(overrides = {}) {
  const dom = new JSDOM(readFileSync('src/operator/agent-projects.html', 'utf8'), { pretendToBeVisual: true });
  const api = { listProjectRepositories: vi.fn().mockResolvedValue([
    { id: 'contracts', name: 'app-afesox', cloned: true }, { id: 'backend', name: 'backend', cloned: true }
  ]) };
  const builder = new WorkflowBuilder({ document: dom.window.document, window: dom.window, api });
  builder.open({ id: 'workflow', name: 'Workflow', nodes: [{ id: 'node', targetId: 'agent', nodeType: 'AGENT',
    scopeMode: 'GLOBAL', inputs: [{id: 'in', name: 'Input', description: 'Input', order: 0}], outputs: [{id: 'out', name: 'Output', description: 'Output', order: 0}],
    position: { x: 0, y: 0 }, ...overrides }], connections: [], taskInputPortId: 'in', taskOutputPortId: 'out' },
    { id: 'project', name: 'Project' }, [{id: 'agent', name: 'API'}]);
  await Promise.resolve(); await Promise.resolve();
  builder.openNodeEditor('node');
  return { builder, document: dom.window.document, dom, api };
}

describe('GLOBAL working repositories', () => {
  it('loads project repositories and shows additive controls', async () => {
    const { document, api, dom } = await setup();
    expect(api.listProjectRepositories).toHaveBeenCalledWith('project');
    expect(document.querySelector('[data-working-include]')).not.toBeNull();
    expect((document.querySelector('[data-working-include]') as HTMLInputElement).checked).toBe(true);
    expect(document.querySelector('[data-working-repository="contracts"]')).not.toBeNull();
    expect(document.body.textContent).toContain('These repositories do not add task scopes.');
    dom.window.close();
  });
  it('preserves selection through search, scope switches and node save/reload', async () => {
    const { builder, document, dom } = await setup({ includeTaskRepositories: false, workspaceRepositoryIds: ['contracts'] });
    const search = document.querySelector('[data-working-search]') as HTMLInputElement;
    expect(search).not.toBeNull();
    search.value = 'backend'; search.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    const scope = () => document.querySelector('[data-node-editor-scope-mode]') as HTMLSelectElement;
    scope().value = 'PER_SCOPE'; scope().dispatchEvent(new dom.window.Event('change'));
    expect(document.querySelector('[data-working-include]')).toBeNull();
    scope().value = 'GLOBAL'; scope().dispatchEvent(new dom.window.Event('change'));
    expect(builder.nodeEditorDraft.workspaceRepositoryIds).toEqual(['contracts']);
    builder.saveNodeEditor(); builder.openNodeEditor('node');
    expect(builder.nodeEditorDraft.includeTaskRepositories).toBe(false);
    expect(builder.nodeEditorDraft.workspaceRepositoryIds).toEqual(['contracts']);
    scope().value = 'PER_SCOPE'; scope().dispatchEvent(new dom.window.Event('change'));
    builder.saveNodeEditor();
    expect(builder.workflow.nodes[0].includeTaskRepositories).toBe(true);
    expect(builder.workflow.nodes[0].workspaceRepositoryIds).toEqual([]);
    dom.window.close();
  });
  it('blocks empty custom selection and keeps unavailable selected UUID visible', async () => {
    const { builder, document, dom } = await setup({ includeTaskRepositories: false, workspaceRepositoryIds: [] });
    builder.saveNodeEditor();
    expect(builder.nodeEditorDraft).not.toBeNull();
    expect(document.body.textContent).toContain('Select at least one working repository');
    builder.nodeEditorDraft.workspaceRepositoryIds = ['deleted-id']; builder.renderNodeEditor();
    expect(document.body.textContent).toContain('deleted-id');
    expect(document.body.textContent).toContain('unavailable');
    builder.saveNodeEditor(); expect(builder.nodeEditorDraft).not.toBeNull();
    dom.window.close();
  });
  it('displays only runtime snapshot repositories and marks unavailable UUIDs', async () => {
    const { document, dom } = await setup();
    const view = new TaskExecutionView({ document, window: dom.window, api: {} });
    view.state.repositories = [{ id: 'contracts', name: 'app-afesox' }, { id: 'task', name: 'task-backend' }];
    view.state.workflowRun = { repositoryIds: ['task'] };
    const html = view.renderWorkingRepositoryDetails({ nodeType: 'AGENT', scopeMode: 'GLOBAL',
      resolvedWorkspaceRepositoryIds: ['contracts', 'removed-id'] });
    expect(html).toContain('Working repositories');
    expect(html).toContain('app-afesox');
    expect(html).toContain('removed-id (unavailable)');
    expect(html).not.toContain('task-backend');
    expect(view.renderWorkingRepositoryDetails({ nodeType: 'AGENT', scopeMode: 'GLOBAL', resolvedWorkspaceRepositoryIds: [] })).toContain('None');
    expect(view.renderWorkingRepositoryDetails({ nodeType: 'MANUAL', scopeMode: 'GLOBAL' })).toBe('');
    expect(view.renderWorkingRepositoryDetails({ nodeType: 'AGENT', scopeMode: 'PER_SCOPE' })).toBe('');
    dom.window.close();
  });
});
