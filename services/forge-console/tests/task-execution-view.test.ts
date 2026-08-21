import { describe, expect, it } from 'vitest';

import { buildExecutionProjection, routeExecutionEdge, visualUnitKey } from '../src/operator/task-execution-view.js';

const REPOSITORIES = [
  { id: 'repo-a', name: 'repo-A' },
  { id: 'repo-b', name: 'repo-B' },
  { id: 'repo-c', name: 'repo-C' }
];

function graph(
  nodes: Array<{ id: string; scopeMode: string }>,
  connections: Array<[string, string]> = [],
  taskInputNodeId: string | null = nodes[0]?.id || null
) {
  return {
    taskInputPortId: taskInputNodeId ? `${taskInputNodeId}-input` : null,
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

function segmentIntersectsRectangle(start: any, end: any, rectangle: any) {
  if (start.x === end.x) {
    return start.x > rectangle.left && start.x < rectangle.right
      && Math.max(start.y, end.y) > rectangle.top
      && Math.min(start.y, end.y) < rectangle.bottom;
  }
  return start.y > rectangle.top && start.y < rectangle.bottom
    && Math.max(start.x, end.x) > rectangle.left
    && Math.min(start.x, end.x) < rectangle.right;
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

  it('keeps task-input re-entry layering stable when connection IDs and order change', () => {
    const nodes = [
      { id: 'implementer', scopeMode: 'PER_SCOPE' },
      { id: 'reviewer', scopeMode: 'PER_SCOPE' }
    ];
    const forwardFirst = graph(nodes, [['implementer', 'reviewer'], ['reviewer', 'implementer']], 'implementer');
    forwardFirst.connections = [
      { ...forwardFirst.connections[0]!, sourceConnectionId: 'zzz-forward' },
      { ...forwardFirst.connections[1]!, sourceConnectionId: 'aaa-feedback' }
    ];
    const feedbackFirst = graph(nodes, [['reviewer', 'implementer'], ['implementer', 'reviewer']], 'implementer');
    feedbackFirst.connections = [
      { ...feedbackFirst.connections[0]!, sourceConnectionId: '000-feedback' },
      { ...feedbackFirst.connections[1]!, sourceConnectionId: '999-forward' }
    ];

    const first = buildExecutionProjection(forwardFirst, ['repo-a', 'repo-b'], REPOSITORIES);
    const second = buildExecutionProjection(feedbackFirst, ['repo-a', 'repo-b'], REPOSITORIES);
    const positions = (projection: ReturnType<typeof buildExecutionProjection>) => new Map(
      projection.nodes.map((node) => [node.visualUnitKey, node.position.x])
    );

    expect(positions(second)).toEqual(positions(first));
    expect(first.nodes.filter((node) => node.sourceNodeId === 'implementer').map((node) => node.position.x)).toEqual([80, 80]);
    expect(first.nodes.filter((node) => node.sourceNodeId === 'reviewer').map((node) => node.position.x)).toEqual([460, 460]);
  });

  it('uses an unavailable label instead of a repository ID when metadata is missing', () => {
    const projection = buildExecutionProjection(
      graph([{ id: 'worker', scopeMode: 'PER_SCOPE' }]),
      ['missing-repository-id'],
      []
    );

    expect(projection.nodes[0]!.repositoryName).toBe('Repository unavailable');
    expect(projection.nodes[0]!.repositoryName).not.toContain('missing-repository-id');
  });

  it('routes every edge around all unrelated visual units with clearance', () => {
    const projection = buildExecutionProjection(graph([
      { id: 'source', scopeMode: 'GLOBAL' },
      { id: 'obstacle-a', scopeMode: 'GLOBAL' },
      { id: 'obstacle-b', scopeMode: 'GLOBAL' },
      { id: 'target', scopeMode: 'GLOBAL' }
    ], [
      ['source', 'obstacle-a'],
      ['obstacle-a', 'obstacle-b'],
      ['obstacle-b', 'target'],
      ['source', 'target']
    ]), [], []);
    const source = projection.nodes.find((node) => node.sourceNodeId === 'source')!;
    const target = projection.nodes.find((node) => node.sourceNodeId === 'target')!;
    const bounds = projection.nodes.map((node) => ({
      key: node.visualUnitKey,
      left: node.position.x,
      top: node.position.y,
      right: node.position.x + 232,
      bottom: node.position.y + node.layoutHeight
    }));
    const route = routeExecutionEdge(
      { x: source.position.x + 232, y: source.position.y + (source.layoutHeight / 2) },
      { x: target.position.x, y: target.position.y + (target.layoutHeight / 2) },
      bounds
    );
    const unrelated = bounds
      .filter((rectangle) => ![source.visualUnitKey, target.visualUnitKey].includes(rectangle.key))
      .map((rectangle) => ({
        ...rectangle,
        left: rectangle.left - 15,
        top: rectangle.top - 15,
        right: rectangle.right + 15,
        bottom: rectangle.bottom + 15
      }));

    for (let index = 1; index < route.length; index += 1) {
      for (const rectangle of unrelated) {
        expect(segmentIntersectsRectangle(route[index - 1], route[index], rectangle)).toBe(false);
      }
    }
    expect(route.some((point) => point.y <= Math.min(...unrelated.map((rectangle) => rectangle.top))
      || point.y >= Math.max(...unrelated.map((rectangle) => rectangle.bottom)))).toBe(true);
  });
});
