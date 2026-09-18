import { describe, expect, it, vi } from 'vitest';
import { HttpError } from '../src/api/http-client';
import { createAgentProjectsApi } from '../src/operator/agent-projects-api.js';

describe('agent projects API manual node selection', () => {
  it('posts the selected output port to the escaped node-run route and returns the updated workflow run', async () => {
    const outputPortId = '77777777-7777-4777-8777-777777777777';
    const updatedWorkflowRun = {
      id: 'workflow/run',
      status: 'RUNNING',
      nodeRuns: [{ id: 'node/run', status: 'SUCCEEDED', selectedOutputPortId: outputPortId }]
    };
    const post = vi.fn().mockResolvedValue(updatedWorkflowRun);
    const client = createAgentProjectsApi({ post });

    await expect(client.selectManualNodeOutput('workflow/run', 'node/run', outputPortId))
      .resolves.toBe(updatedWorkflowRun);
    expect(post).toHaveBeenCalledOnce();
    expect(post).toHaveBeenCalledWith(
      '/agents/workflow-runs/workflow%2Frun/node-runs/node%2Frun/manual-selection',
      { outputPortId: '77777777-7777-4777-8777-777777777777' }
    );
  });

  it('preserves typed HTTP errors from a rejected manual selection', async () => {
    const error = new HttpError(
      409,
      { code: 'MANUAL_NODE_SELECTION_INVALID' },
      'Manual selection rejected'
    );
    const client = createAgentProjectsApi({ post: vi.fn().mockRejectedValue(error) });

    await expect(client.selectManualNodeOutput(
      'run-id',
      'node-run-id',
      '77777777-7777-4777-8777-777777777777'
    ))
      .rejects.toBe(error);
  });
});
