import { describe, expect, it } from 'vitest';

import {
  buildExecutionProjection,
  executionConnectionsMayBundle,
  positiveLengthSegmentOverlap,
  routeExecutionEdge,
  routesSharePositiveLengthSegment,
  visualUnitKey
} from '../src/operator/task-execution-view.js';

const REPOSITORIES = [
  { id: 'repo-a', name: 'repo-A' },
  { id: 'repo-b', name: 'repo-B' },
  { id: 'repo-c', name: 'repo-C' }
];
const MODERN_EXECUTION_CARD_WIDTH = 288;

function graph(
  nodes: Array<{ id: string; scopeMode: string }>,
  connections: Array<[string, string]> = [],
  taskInputNodeId: string | null = null,
  taskOutputNodeId: string | null = null
) {
  return {
    taskInputPortId: taskInputNodeId ? `${taskInputNodeId}-input` : null,
    taskOutputPortId: taskOutputNodeId ? `${taskOutputNodeId}-output` : null,
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
  const leftWidth = left.layoutWidth || 288;
  const rightWidth = right.layoutWidth || 288;
  return left.position.x < right.position.x + rightWidth
    && left.position.x + leftWidth > right.position.x
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
    expect(first.nodes.filter((node) => node.sourceNodeId === 'implementer').map((node) => node.position.x)).toEqual([360, 360]);
    expect(first.nodes.filter((node) => node.sourceNodeId === 'reviewer').map((node) => node.position.x)).toEqual([796, 796]);
  });

  it('projects canonical task boundaries without inventing execution units', () => {
    const projection = buildExecutionProjection(
      graph(
        [{ id: 'root', scopeMode: 'PER_SCOPE' }, { id: 'finish', scopeMode: 'GLOBAL' }],
        [['root', 'finish']],
        'root',
        'finish'
      ),
      ['repo-a', 'repo-b'],
      REPOSITORIES
    );

    const taskInput = projection.nodes.find((node) => node.taskBoundary === 'INPUT')!;
    const taskOutput = projection.nodes.find((node) => node.taskBoundary === 'OUTPUT')!;
    const inputEdges = projection.connections.filter((connection) => connection.sourceVisualUnitKey === taskInput.visualUnitKey);
    const outputEdges = projection.connections.filter((connection) => connection.targetVisualUnitKey === taskOutput.visualUnitKey);

    expect(inputEdges.map((edge) => edge.targetVisualUnitKey)).toEqual([
      visualUnitKey('root', 'repo-a'),
      visualUnitKey('root', 'repo-b')
    ]);
    expect(inputEdges.every((edge) => edge.targetInputPortId === 'root-input')).toBe(true);
    expect(outputEdges).toHaveLength(1);
    expect(outputEdges[0]!.sourceOutputPortId).toBe('finish-output');
    expect(taskInput.position.x).toBeLessThan(projection.nodes.find((node) => node.sourceNodeId === 'root')!.position.x);
    expect(taskOutput.position.x).toBeGreaterThan(projection.nodes.find((node) => node.sourceNodeId === 'finish')!.position.x);
    expect(projection.nodes.filter((node) => !node.taskBoundary)).toHaveLength(3);
  });

  it('preserves a direct self-loop as feedback topology', () => {
    const runtimeGraph = graph([{ id: 'reviewer', scopeMode: 'GLOBAL' }]);
    runtimeGraph.connections = [{
      sourceConnectionId: 'reviewer-self',
      sourceOutputPortId: 'reviewer-output',
      targetInputPortId: 'reviewer-input'
    }];

    const projection = buildExecutionProjection(runtimeGraph, [], []);

    expect(projectedPairs(projection)).toEqual([
      [visualUnitKey('reviewer', null), visualUnitKey('reviewer', null)]
    ]);
    expect(projection.connections[0]!.visualType).toBe('SELF_LOOP');
  });

  it('builds a deterministic primary tree and classifies fan-in and feedback edges', () => {
    const nodes = [
      { id: 'root', scopeMode: 'GLOBAL' },
      { id: 'left', scopeMode: 'GLOBAL' },
      { id: 'right', scopeMode: 'GLOBAL' },
      { id: 'merge', scopeMode: 'GLOBAL' },
      { id: 'finish', scopeMode: 'GLOBAL' }
    ];
    const connections: Array<[string, string]> = [
      ['root', 'left'],
      ['root', 'right'],
      ['left', 'merge'],
      ['right', 'merge'],
      ['merge', 'finish'],
      ['finish', 'left'],
      ['merge', 'merge']
    ];
    const original = graph(nodes, connections, 'root', 'finish');
    const shuffled = graph(nodes, connections.slice().reverse(), 'root', 'finish');
    shuffled.connections.forEach((connection, index) => {
      connection.sourceConnectionId = `shuffled-${index}`;
    });

    const first = buildExecutionProjection(original, [], []);
    const second = buildExecutionProjection(shuffled, [], []);
    const positions = (projection: ReturnType<typeof buildExecutionProjection>) => Object.fromEntries(
      projection.nodes.map((node) => [node.visualUnitKey, node.position])
    );
    const edgeLayout = (projection: ReturnType<typeof buildExecutionProjection>) => Object.fromEntries(
      projection.connections.map((edge) => [
        `${edge.sourceVisualUnitKey}->${edge.targetVisualUnitKey}`,
        [edge.visualType, edge.visualLane ?? null]
      ])
    );
    const byPair = new Map(first.connections.map((edge) => [
      `${edge.sourceVisualUnitKey}->${edge.targetVisualUnitKey}`,
      edge.visualType
    ]));

    expect(positions(second)).toEqual(positions(first));
    expect(edgeLayout(second)).toEqual(edgeLayout(first));
    expect(first.nodes.find((node) => node.taskBoundary === 'INPUT')!.position.x)
      .toBeLessThan(first.nodes.find((node) => node.sourceNodeId === 'root')!.position.x);
    expect(first.nodes.find((node) => node.taskBoundary === 'OUTPUT')!.position.x)
      .toBeGreaterThan(first.nodes.find((node) => node.sourceNodeId === 'finish')!.position.x);
    expect(first.nodes.find((node) => node.sourceNodeId === 'left')!.position.x)
      .toBeGreaterThan(first.nodes.find((node) => node.sourceNodeId === 'root')!.position.x);
    expect(byPair.get(`${visualUnitKey('right', null)}->${visualUnitKey('merge', null)}`)).toBe('SECONDARY_FAN_IN');
    expect(byPair.get(`${visualUnitKey('finish', null)}->${visualUnitKey('left', null)}`)).toBe('FEEDBACK_REENTRY');
    expect(byPair.get(`${visualUnitKey('merge', null)}->${visualUnitKey('merge', null)}`)).toBe('SELF_LOOP');
    for (let left = 0; left < first.nodes.length; left += 1) {
      for (let right = left + 1; right < first.nodes.length; right += 1) {
        expect(rectanglesOverlap(first.nodes[left]!, first.nodes[right]!)).toBe(false);
      }
    }
  });

  it('lays out a selector/reviewer/implementation cycle as stable left-to-right branches', () => {
    const runtimeGraph = graph([
      { id: 'selector', scopeMode: 'GLOBAL' },
      { id: 'reviewer', scopeMode: 'GLOBAL' },
      { id: 'implementation', scopeMode: 'GLOBAL' },
      { id: 'planner', scopeMode: 'GLOBAL' },
      { id: 'implementer', scopeMode: 'GLOBAL' }
    ], [
      ['selector', 'reviewer'],
      ['selector', 'implementation'],
      ['reviewer', 'planner'],
      ['planner', 'implementer'],
      ['implementer', 'reviewer'],
      ['reviewer', 'selector']
    ], 'selector', 'selector');

    const projection = buildExecutionProjection(runtimeGraph, [], []);
    const x = (id: string) => projection.nodes.find((node) => node.sourceNodeId === id)!.position.x;

    expect(x('reviewer')).toBeGreaterThan(x('selector'));
    expect(x('implementation')).toBeGreaterThan(x('selector'));
    expect(x('planner')).toBeGreaterThan(x('reviewer'));
    expect(x('implementer')).toBeGreaterThan(x('planner'));
    expect(projection.nodes.find((node) => node.taskBoundary === 'OUTPUT')!.position.x).toBeGreaterThan(x('implementer'));
    expect(projection.connections.filter((edge) => edge.visualType === 'FEEDBACK_REENTRY')).toHaveLength(2);
  });

  it('distinguishes valid point crossings from positive-length segment overlap', () => {
    expect(positiveLengthSegmentOverlap(
      { x: 0, y: 20 }, { x: 100, y: 20 },
      { x: 50, y: 0 }, { x: 50, y: 40 }
    )).toBe(false);
    expect(positiveLengthSegmentOverlap(
      { x: 0, y: 20 }, { x: 100, y: 20 },
      { x: 40, y: 20 }, { x: 140, y: 20 }
    )).toBe(true);
    expect(routesSharePositiveLengthSegment(
      [{ x: 0, y: 20 }, { x: 100, y: 20 }],
      [{ x: 50, y: 0 }, { x: 50, y: 40 }]
    )).toBe(false);
  });

  it('allows bundling only for the exact same projected source or target pin', () => {
    const edge = (overrides: Record<string, string>) => ({
      sourceVisualUnitKey: 'source-a',
      sourceOutputPortId: 'source-output-a',
      targetVisualUnitKey: 'target-a',
      targetInputPortId: 'target-input-a',
      ...overrides
    });
    const base = edge({});

    expect(executionConnectionsMayBundle(base, edge({ targetVisualUnitKey: 'target-b', targetInputPortId: 'target-input-b' }))).toBe(true);
    expect(executionConnectionsMayBundle(base, edge({ sourceVisualUnitKey: 'source-b', sourceOutputPortId: 'source-output-b' }))).toBe(true);
    expect(executionConnectionsMayBundle(base, edge({ sourceOutputPortId: 'source-output-b', targetInputPortId: 'target-input-b' }))).toBe(false);
    expect(executionConnectionsMayBundle(base, edge({ sourceVisualUnitKey: 'source-b', targetVisualUnitKey: 'target-b' }))).toBe(false);
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
      right: node.position.x + Number(node.layoutWidth || MODERN_EXECUTION_CARD_WIDTH),
      bottom: node.position.y + node.layoutHeight
    }));
    const route = routeExecutionEdge(
      { x: source.position.x + Number(source.layoutWidth || MODERN_EXECUTION_CARD_WIDTH), y: source.position.y + (source.layoutHeight / 2) },
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

  it('falls back to a distinct outer lane when occupied segments isolate the compact route', async () => {
    const taskExecutionModule = await import('../src/operator/task-execution-view.js') as any;
    const view = Object.create(taskExecutionModule.TaskExecutionView.prototype);
    view.modernNodeBounds = (node: any) => ({
      key: node.visualUnitKey,
      left: node.position.x,
      top: node.position.y,
      right: node.position.x + node.layoutWidth,
      bottom: node.position.y + node.layoutHeight
    });
    const source = {
      visualUnitKey: 'source',
      position: { x: 0, y: 0 },
      layoutWidth: MODERN_EXECUTION_CARD_WIDTH,
      layoutHeight: 120
    };
    const target = {
      visualUnitKey: 'target',
      position: { x: 500, y: 0 },
      layoutWidth: MODERN_EXECUTION_CARD_WIDTH,
      layoutHeight: 120
    };
    const connection = {
      sourceVisualUnitKey: 'source',
      sourceOutputPortId: 'source-output',
      targetVisualUnitKey: 'target',
      targetInputPortId: 'target-input',
      visualType: 'PRIMARY_FORWARD'
    };
    const occupiedRoute = {
      connection: {
        sourceVisualUnitKey: 'unrelated-source',
        sourceOutputPortId: 'unrelated-output',
        targetVisualUnitKey: 'unrelated-target',
        targetInputPortId: 'unrelated-input'
      },
      points: [
        { x: 296, y: 52 },
        { x: 312, y: 52 },
        { x: 312, y: 68 },
        { x: 296, y: 68 },
        { x: 296, y: 52 }
      ]
    };

    const route = view.modernRoutePoints(
      { x: MODERN_EXECUTION_CARD_WIDTH, y: 60 },
      { x: 500, y: 60 },
      source,
      target,
      { graph: { nodes: [source, target] } },
      connection,
      [occupiedRoute]
    );

    expect(route.some((point: any) => point.y > 120)).toBe(true);
    expect(routesSharePositiveLengthSegment(route, occupiedRoute.points)).toBe(false);
  });
});
