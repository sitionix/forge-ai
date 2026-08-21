import { describe, expect, it } from 'vitest';

import { buildExecutionProjection, visualUnitKey } from '../src/operator/task-execution-view.js';

const REPOSITORIES = [
  { id: 'repo-a', name: 'repo-A' },
  { id: 'repo-b', name: 'repo-B' },
  { id: 'repo-c', name: 'repo-C' }
];

function graph(nodes: Array<{ id: string; scopeMode: string }>, connections: Array<[string, string]> = []) {
  return {
    nodes: nodes.map((node, index) => ({
      sourceNodeId: node.id,
      agentName: node.id,
      scopeMode: node.scopeMode,
      position: { x: 20 + (index * 280), y: 30 }
    })),
    ports: nodes.flatMap((node) => [
      { sourcePortId: `${node.id}-input`, sourceNodeId: node.id, direction: 'INPUT', name: 'Input', order: 0 },
      { sourcePortId: `${node.id}-output`, sourceNodeId: node.id, direction: 'OUTPUT', name: 'Output', order: 0 }
    ]),
    connections: connections.map(([source, target]) => ({
      sourceConnectionId: `${source}-${target}`,
      sourceOutputPortId: `${source}-output`,
      targetInputPortId: `${target}-input`
    }))
  };
}

function projectedPairs(projection: ReturnType<typeof buildExecutionProjection>) {
  return projection.connections.map((connection) => [
    connection.sourceVisualUnitKey,
    connection.targetVisualUnitKey
  ]);
}

function rectanglesOverlap(left: any, right: any) {
  return left.position.x < right.position.x + 232
    && left.position.x + 232 > right.position.x
    && left.position.y < right.position.y + right.layoutHeight
    && left.position.y + left.layoutHeight > right.position.y;
}

describe('task execution visual projection', () => {
  it('keeps GLOBAL to GLOBAL as two unlabeled units and one edge', () => {
    const projection = buildExecutionProjection(
      graph([{ id: 'a', scopeMode: 'GLOBAL' }, { id: 'b', scopeMode: 'GLOBAL' }], [['a', 'b']]),
      ['repo-a'],
      REPOSITORIES
    );

    expect(projection.nodes).toHaveLength(2);
    expect(projection.nodes.every((node) => node.repositoryId === null && node.repositoryName === null)).toBe(true);
    expect(projectedPairs(projection)).toEqual([[visualUnitKey('a', null), visualUnitKey('b', null)]]);
  });

  it('fans GLOBAL out to every scoped repository in snapshot order', () => {
    const projection = buildExecutionProjection(
      graph([{ id: 'a', scopeMode: 'GLOBAL' }, { id: 'b', scopeMode: 'PER_SCOPE' }], [['a', 'b']]),
      ['repo-b', 'repo-a'],
      REPOSITORIES
    );

    expect(projection.nodes.filter((node) => node.sourceNodeId === 'b').map((node) => [node.repositoryId, node.repositoryName]))
      .toEqual([['repo-b', 'repo-B'], ['repo-a', 'repo-A']]);
    expect(projectedPairs(projection)).toEqual([
      [visualUnitKey('a', null), visualUnitKey('b', 'repo-b')],
      [visualUnitKey('a', null), visualUnitKey('b', 'repo-a')]
    ]);
  });

  it('zips PER_SCOPE units by repository and never creates a cartesian edge set', () => {
    const projection = buildExecutionProjection(
      graph([{ id: 'a', scopeMode: 'PER_SCOPE' }, { id: 'b', scopeMode: 'PER_SCOPE' }], [['a', 'b']]),
      ['repo-a', 'repo-b'],
      REPOSITORIES
    );

    expect(projectedPairs(projection)).toEqual([
      [visualUnitKey('a', 'repo-a'), visualUnitKey('b', 'repo-a')],
      [visualUnitKey('a', 'repo-b'), visualUnitKey('b', 'repo-b')]
    ]);
  });

  it('fans every scoped repository into one GLOBAL target', () => {
    const projection = buildExecutionProjection(
      graph([{ id: 'a', scopeMode: 'PER_SCOPE' }, { id: 'b', scopeMode: 'GLOBAL' }], [['a', 'b']]),
      ['repo-a', 'repo-b'],
      REPOSITORIES
    );

    expect(projectedPairs(projection)).toEqual([
      [visualUnitKey('a', 'repo-a'), visualUnitKey('b', null)],
      [visualUnitKey('a', 'repo-b'), visualUnitKey('b', null)]
    ]);
  });

  it('lays out three repositories deterministically with even spacing and no overlap', () => {
    const runtimeGraph = graph([
      { id: 'a', scopeMode: 'GLOBAL' },
      { id: 'b', scopeMode: 'PER_SCOPE' },
      { id: 'c', scopeMode: 'PER_SCOPE' },
      { id: 'd', scopeMode: 'GLOBAL' }
    ], [['a', 'b'], ['b', 'c'], ['c', 'd']]);
    const projection = buildExecutionProjection(runtimeGraph, REPOSITORIES.map((repository) => repository.id), REPOSITORIES);
    const repeated = buildExecutionProjection(runtimeGraph, REPOSITORIES.map((repository) => repository.id), REPOSITORIES);
    const bUnits = projection.nodes.filter((node) => node.sourceNodeId === 'b');

    expect(bUnits.map((node) => node.repositoryId)).toEqual(['repo-a', 'repo-b', 'repo-c']);
    expect(bUnits[1]!.position.y - bUnits[0]!.position.y).toBe(bUnits[2]!.position.y - bUnits[1]!.position.y);
    for (let left = 0; left < projection.nodes.length; left += 1) {
      for (let right = left + 1; right < projection.nodes.length; right += 1) {
        expect(rectanglesOverlap(projection.nodes[left]!, projection.nodes[right]!)).toBe(false);
      }
    }
    expect(repeated.nodes.map((node) => node.position)).toEqual(projection.nodes.map((node) => node.position));
    expect(projection.connections).toHaveLength(9);
  });
});
