import { escapeHtml } from './dom-render-helpers.js';

const ACTIVE_RUN_STATUSES = new Set(['QUEUED', 'RUNNING']);
const NODE_WIDTH = 204;
const NODE_HEIGHT = 110;
const MODERN_NODE_WIDTH = 288;
const TASK_BOUNDARY_WIDTH = 132;
const TASK_BOUNDARY_HEIGHT = 58;
const MODERN_NODE_FALLBACK_HEIGHT = 118;
const MODERN_SCOPED_NODE_FALLBACK_HEIGHT = 136;
const MODERN_PORT_ROW_HEIGHT = 24;
const NODE_MID_Y = 58;
const MIN_CANVAS_WIDTH = 1600;
const MIN_CANVAS_HEIGHT = 1000;
const CANVAS_PADDING = 240;
const MIN_CANVAS_SCALE = 0.45;
const MAX_CANVAS_SCALE = 1.8;
const HISTORY_MARKER_LIMIT = 6;
const LAYOUT_X_GAP = 148;
const LAYOUT_Y_GAP = 56;
const LAYOUT_START_X = 80;
const LAYOUT_START_Y = 80;
const GLOBAL_REPOSITORY_KEY = '__global__';
const EDGE_NODE_CLEARANCE = 16;
const EDGE_BEND_COST = 12;
const UNAVAILABLE_REPOSITORY_LABEL = 'Repository unavailable';
const SELECTION_FOLLOW_LATEST = 'FOLLOW_LATEST';
const SELECTION_PINNED_INVOCATION = 'PINNED_INVOCATION';
const TASK_INPUT_UNIT_KEY = '__task_input__';
const TASK_OUTPUT_UNIT_KEY = '__task_output__';
const TASK_INPUT_NODE_ID = '__task_input_node__';
const TASK_OUTPUT_NODE_ID = '__task_output_node__';
const TASK_INPUT_OUTPUT_PORT_ID = '__task_input_output__';
const TASK_OUTPUT_INPUT_PORT_ID = '__task_output_input__';

export function visualUnitKey(sourceNodeId, repositoryId) {
  return `${sourceNodeId}::${normalizedRepositoryId(repositoryId) || GLOBAL_REPOSITORY_KEY}`;
}

function normalizedRepositoryId(repositoryId) {
  return repositoryId || null;
}

function portAnchorKey(unitKey, portId) {
  return unitKey.endsWith(`::${GLOBAL_REPOSITORY_KEY}`) ? portId : `${unitKey}::${portId}`;
}

export function buildExecutionProjection(runtimeGraph, repositoryIds = [], repositories = []) {
  const logicalNodes = runtimeGraph?.nodes || [];
  const ports = [...(runtimeGraph?.ports || [])];
  const logicalConnections = runtimeGraph?.connections || [];
  const repositoryNameById = new Map(repositories.map((repository) => [repository.id, repository.name]));
  const repositoryOrder = new Map(repositoryIds.map((repositoryId, index) => [repositoryId, index]));
  const unitsBySource = new Map();
  const units = [];

  for (const [logicalIndex, logicalNode] of logicalNodes.entries()) {
    const scopeMode = logicalNode.scopeMode || 'GLOBAL';
    const invocationRepositories = visualUnitRepositories(scopeMode, repositoryIds);
    const sourceUnits = invocationRepositories.map((repositoryId) => {
      const unit = {
        ...logicalNode,
        scopeMode,
        repositoryId,
        repositoryName: repositoryId ? repositoryNameById.get(repositoryId) || UNAVAILABLE_REPOSITORY_LABEL : null,
        visualUnitKey: visualUnitKey(logicalNode.sourceNodeId, repositoryId),
        logicalIndex
      };
      units.push(unit);
      return unit;
    });
    unitsBySource.set(logicalNode.sourceNodeId, sourceUnits);
  }

  const portById = new Map(ports.map((port) => [port.sourcePortId, port]));
  const projectedConnections = [];
  for (const connection of logicalConnections) {
    const sourcePort = portById.get(connection.sourceOutputPortId);
    const targetPort = portById.get(connection.targetInputPortId);
    const sourceUnits = unitsBySource.get(sourcePort?.sourceNodeId) || [];
    const targetUnits = unitsBySource.get(targetPort?.sourceNodeId) || [];
    if (!sourceUnits.length || !targetUnits.length) {
      continue;
    }
    for (const [sourceUnit, targetUnit] of projectedUnitPairs(sourceUnits, targetUnits)) {
      projectedConnections.push({
        ...connection,
        sourceVisualUnitKey: sourceUnit.visualUnitKey,
        targetVisualUnitKey: targetUnit.visualUnitKey
      });
    }
  }

  const taskInputNodeId = portById.get(runtimeGraph?.taskInputPortId)?.sourceNodeId || null;
  const taskOutputNodeId = portById.get(runtimeGraph?.taskOutputPortId)?.sourceNodeId || null;
  const rootUnits = unitsBySource.get(taskInputNodeId) || [];
  if (runtimeGraph?.taskInputPortId && rootUnits.length) {
    const boundary = taskBoundaryUnit('INPUT');
    const boundaryPort = taskBoundaryPort('OUTPUT');
    units.unshift(boundary);
    ports.push(boundaryPort);
    portById.set(boundaryPort.sourcePortId, boundaryPort);
    for (const targetUnit of rootUnits) {
      projectedConnections.push({
        sourceConnectionId: `task-input:${targetUnit.visualUnitKey}`,
        sourceOutputPortId: TASK_INPUT_OUTPUT_PORT_ID,
        targetInputPortId: runtimeGraph.taskInputPortId,
        sourceVisualUnitKey: TASK_INPUT_UNIT_KEY,
        targetVisualUnitKey: targetUnit.visualUnitKey,
        taskBoundary: true
      });
    }
  }
  const outputUnits = unitsBySource.get(taskOutputNodeId) || [];
  if (runtimeGraph?.taskOutputPortId && outputUnits.length) {
    const boundary = taskBoundaryUnit('OUTPUT');
    const boundaryPort = taskBoundaryPort('INPUT');
    units.push(boundary);
    ports.push(boundaryPort);
    portById.set(boundaryPort.sourcePortId, boundaryPort);
    for (const sourceUnit of outputUnits) {
      projectedConnections.push({
        sourceConnectionId: `task-output:${sourceUnit.visualUnitKey}`,
        sourceOutputPortId: runtimeGraph.taskOutputPortId,
        targetInputPortId: TASK_OUTPUT_INPUT_PORT_ID,
        sourceVisualUnitKey: sourceUnit.visualUnitKey,
        targetVisualUnitKey: TASK_OUTPUT_UNIT_KEY,
        taskBoundary: true
      });
    }
  }
  const rootUnitKeys = new Set(runtimeGraph?.taskInputPortId && rootUnits.length
    ? [TASK_INPUT_UNIT_KEY]
    : rootUnits.map((unit) => unit.visualUnitKey));
  layoutExecutionProjection(units, projectedConnections, ports, repositoryOrder, rootUnitKeys);
  return { nodes: units, ports, connections: projectedConnections };
}

function taskBoundaryUnit(kind) {
  const input = kind === 'INPUT';
  return {
    sourceNodeId: input ? TASK_INPUT_NODE_ID : TASK_OUTPUT_NODE_ID,
    visualUnitKey: input ? TASK_INPUT_UNIT_KEY : TASK_OUTPUT_UNIT_KEY,
    agentName: `TASK ${kind}`,
    taskBoundary: kind,
    scopeMode: 'GLOBAL',
    repositoryId: null,
    logicalIndex: input ? -1 : Number.MAX_SAFE_INTEGER,
    layoutWidth: TASK_BOUNDARY_WIDTH,
    layoutHeight: TASK_BOUNDARY_HEIGHT
  };
}

function taskBoundaryPort(direction) {
  const output = direction === 'OUTPUT';
  return {
    sourcePortId: output ? TASK_INPUT_OUTPUT_PORT_ID : TASK_OUTPUT_INPUT_PORT_ID,
    sourceNodeId: output ? TASK_INPUT_NODE_ID : TASK_OUTPUT_NODE_ID,
    direction,
    name: output ? 'Task' : 'Result',
    order: 0,
    taskBoundary: true
  };
}

function visualUnitRepositories(scopeMode, repositoryIds) {
  if (scopeMode === 'GLOBAL') {
    return [null];
  }
  if (scopeMode === 'PER_SCOPE') {
    return repositoryIds;
  }
  throw new Error(`Unsupported execution scope mode: ${scopeMode}`);
}

function projectedUnitPairs(sourceUnits, targetUnits) {
  const sourceMode = sourceUnits[0].scopeMode;
  const targetMode = targetUnits[0].scopeMode;
  if (sourceMode === 'GLOBAL' && targetMode === 'GLOBAL') {
    return [[sourceUnits[0], targetUnits[0]]];
  }
  if (sourceMode === 'GLOBAL' && targetMode === 'PER_SCOPE') {
    return targetUnits.map((targetUnit) => [sourceUnits[0], targetUnit]);
  }
  if (sourceMode === 'PER_SCOPE' && targetMode === 'GLOBAL') {
    return sourceUnits.map((sourceUnit) => [sourceUnit, targetUnits[0]]);
  }
  if (sourceMode === 'PER_SCOPE' && targetMode === 'PER_SCOPE') {
    const targetByRepository = new Map(targetUnits.map((unit) => [unit.repositoryId, unit]));
    return sourceUnits
      .filter((sourceUnit) => targetByRepository.has(sourceUnit.repositoryId))
      .map((sourceUnit) => [sourceUnit, targetByRepository.get(sourceUnit.repositoryId)]);
  }
  throw new Error(`Unsupported execution scope projection: ${sourceMode} -> ${targetMode}`);
}

function layoutExecutionProjection(units, connections, ports, repositoryOrder, rootUnitKeys) {
  const depths = projectionDepths(units, connections, rootUnitKeys);
  const portCountBySource = new Map();
  for (const port of ports) {
    const counts = portCountBySource.get(port.sourceNodeId) || { input: 0, output: 0 };
    counts[port.direction === 'OUTPUT' ? 'output' : 'input'] += 1;
    portCountBySource.set(port.sourceNodeId, counts);
  }
  const columns = new Map();
  for (const unit of units) {
    const depth = depths.get(unit.visualUnitKey) || 0;
    if (!columns.has(depth)) {
      columns.set(depth, []);
    }
    if (unit.taskBoundary) {
      columns.get(depth).push(unit);
      continue;
    }
    const counts = portCountBySource.get(unit.sourceNodeId) || { input: 0, output: 0 };
    const scopeLabelHeight = unit.repositoryId ? 18 : 0;
    const fallbackHeight = unit.repositoryId ? MODERN_SCOPED_NODE_FALLBACK_HEIGHT : MODERN_NODE_FALLBACK_HEIGHT;
    unit.layoutHeight = Math.max(fallbackHeight, 58 + scopeLabelHeight + (Math.max(counts.input, counts.output) * MODERN_PORT_ROW_HEIGHT));
    columns.get(depth).push(unit);
  }
  for (const column of columns.values()) {
    column.sort((left, right) => {
      const leftRepository = left.repositoryId == null ? -1 : repositoryOrder.get(left.repositoryId) ?? Number.MAX_SAFE_INTEGER;
      const rightRepository = right.repositoryId == null ? -1 : repositoryOrder.get(right.repositoryId) ?? Number.MAX_SAFE_INTEGER;
      return leftRepository - rightRepository || left.logicalIndex - right.logicalIndex;
    });
  }
  const columnHeights = [...columns.values()].map((column) => column.reduce((height, unit, index) => (
    height + unit.layoutHeight + (index ? LAYOUT_Y_GAP : 0)
  ), 0));
  const maxHeight = Math.max(0, ...columnHeights);
  const sortedDepths = [...columns.keys()].sort((left, right) => left - right);
  const xByDepth = new Map();
  let x = LAYOUT_START_X;
  for (const depth of sortedDepths) {
    xByDepth.set(depth, x);
    x += Math.max(...columns.get(depth).map((unit) => unit.layoutWidth || MODERN_NODE_WIDTH)) + LAYOUT_X_GAP;
  }
  for (const [depth, column] of columns) {
    const columnHeight = column.reduce((height, unit, index) => height + unit.layoutHeight + (index ? LAYOUT_Y_GAP : 0), 0);
    let y = LAYOUT_START_Y + ((maxHeight - columnHeight) / 2);
    for (const unit of column) {
      unit.position = {
        x: xByDepth.get(depth),
        y
      };
      y += unit.layoutHeight + LAYOUT_Y_GAP;
    }
  }
}

function projectionDepths(units, connections, rootUnitKeys) {
  const depth = new Map(units.map((unit) => [unit.visualUnitKey, 0]));
  const outgoing = new Map(units.map((unit) => [unit.visualUnitKey, []]));
  const unitOrder = new Map(units.map((unit, index) => [unit.visualUnitKey, index]));
  const candidatesBySource = new Map(units.map((unit) => [unit.visualUnitKey, []]));
  for (const connection of connections) {
    candidatesBySource.get(connection.sourceVisualUnitKey)?.push(connection);
  }
  for (const candidates of candidatesBySource.values()) {
    candidates.sort((left, right) => compareProjectionConnections(left, right, unitOrder));
  }
  const forwardConnections = rootedForwardConnections(
    units,
    candidatesBySource,
    outgoing,
    rootUnitKeys,
    unitOrder
  );
  for (let pass = 0; pass < units.length; pass += 1) {
    let changed = false;
    for (const connection of forwardConnections) {
      const nextDepth = (depth.get(connection.sourceVisualUnitKey) || 0) + 1;
      if (nextDepth > (depth.get(connection.targetVisualUnitKey) || 0)) {
        depth.set(connection.targetVisualUnitKey, nextDepth);
        changed = true;
      }
    }
    if (!changed) {
      break;
    }
  }
  return depth;
}

function rootedForwardConnections(units, candidatesBySource, outgoing, rootUnitKeys, unitOrder) {
  const forwardConnections = [];
  const visiting = new Set();
  const visited = new Set();
  const traverse = (unitKey) => {
    if (visited.has(unitKey)) {
      return;
    }
    visiting.add(unitKey);
    for (const connection of candidatesBySource.get(unitKey) || []) {
      const targetKey = connection.targetVisualUnitKey;
      if ((rootUnitKeys.has(targetKey) && targetKey !== unitKey)
        || visiting.has(targetKey)
        || hasProjectionPath(outgoing, targetKey, unitKey)) {
        continue;
      }
      outgoing.get(unitKey)?.push(targetKey);
      forwardConnections.push(connection);
      traverse(targetKey);
    }
    visiting.delete(unitKey);
    visited.add(unitKey);
  };
  const orderedRoots = [...rootUnitKeys].sort((left, right) => compareUnitKeys(left, right, unitOrder));
  for (const rootKey of orderedRoots) {
    traverse(rootKey);
  }
  const remaining = units.map((unit) => unit.visualUnitKey)
    .sort((left, right) => compareUnitKeys(left, right, unitOrder));
  for (const unitKey of remaining) {
    traverse(unitKey);
  }
  return forwardConnections;
}

function compareProjectionConnections(left, right, unitOrder) {
  return compareUnitKeys(left.targetVisualUnitKey, right.targetVisualUnitKey, unitOrder)
    || compareUnitKeys(left.sourceVisualUnitKey, right.sourceVisualUnitKey, unitOrder)
    || String(left.sourceOutputPortId || '').localeCompare(String(right.sourceOutputPortId || ''))
    || String(left.targetInputPortId || '').localeCompare(String(right.targetInputPortId || ''));
}

function compareUnitKeys(left, right, unitOrder) {
  return (unitOrder.get(left) ?? Number.MAX_SAFE_INTEGER) - (unitOrder.get(right) ?? Number.MAX_SAFE_INTEGER)
    || String(left).localeCompare(String(right));
}

function hasProjectionPath(outgoing, start, target) {
  const pending = [start];
  const visited = new Set();
  while (pending.length) {
    const current = pending.pop();
    if (current === target) {
      return true;
    }
    if (visited.has(current)) {
      continue;
    }
    visited.add(current);
    pending.push(...(outgoing.get(current) || []));
  }
  return false;
}

export function routeExecutionEdge(start, end, nodeBounds) {
  const sourceBounds = nodeBounds.find((bounds) => pointInsideOrOnRectangle(start, bounds));
  const targetBounds = nodeBounds.find((bounds) => pointInsideOrOnRectangle(end, bounds));
  const obstacles = nodeBounds.map((bounds) => ({
    left: bounds.left - EDGE_NODE_CLEARANCE,
    top: bounds.top - EDGE_NODE_CLEARANCE,
    right: bounds.right + EDGE_NODE_CLEARANCE,
    bottom: bounds.bottom + EDGE_NODE_CLEARANCE
  }));
  const sourceExit = {
    x: sourceBounds ? sourceBounds.right + EDGE_NODE_CLEARANCE : start.x + EDGE_NODE_CLEARANCE,
    y: start.y
  };
  const targetEntry = {
    x: targetBounds ? targetBounds.left - EDGE_NODE_CLEARANCE : end.x - EDGE_NODE_CLEARANCE,
    y: end.y
  };
  const routed = shortestOrthogonalRoute(sourceExit, targetEntry, obstacles);
  return compactOrthogonalPoints([start, ...routed, end]);
}

function shortestOrthogonalRoute(start, end, obstacles) {
  const xCoordinates = new Set([start.x, end.x]);
  const yCoordinates = new Set([start.y, end.y]);
  for (const obstacle of obstacles) {
    xCoordinates.add(obstacle.left);
    xCoordinates.add(obstacle.right);
    yCoordinates.add(obstacle.top);
    yCoordinates.add(obstacle.bottom);
  }
  const xs = [...xCoordinates].sort((left, right) => left - right);
  const ys = [...yCoordinates].sort((left, right) => left - right);
  const pointByKey = new Map();
  for (const y of ys) {
    for (const x of xs) {
      const point = { x, y };
      if (obstacles.some((obstacle) => pointInsideObstacle(point, obstacle))) {
        continue;
      }
      pointByKey.set(pointKey(point), point);
    }
  }
  pointByKey.set(pointKey(start), start);
  pointByKey.set(pointKey(end), end);
  const adjacency = new Map([...pointByKey.keys()].map((key) => [key, []]));
  connectVisibleNeighbors([...pointByKey.values()], 'x', 'y', obstacles, adjacency);
  connectVisibleNeighbors([...pointByKey.values()], 'y', 'x', obstacles, adjacency);
  const route = findShortestRoute(start, end, pointByKey, adjacency);
  if (!route) {
    throw new Error('Execution edge could not be routed without intersecting a node.');
  }
  return route;
}

function connectVisibleNeighbors(points, groupAxis, sortAxis, obstacles, adjacency) {
  const groups = new Map();
  for (const point of points) {
    if (!groups.has(point[groupAxis])) {
      groups.set(point[groupAxis], []);
    }
    groups.get(point[groupAxis]).push(point);
  }
  for (const group of groups.values()) {
    group.sort((left, right) => left[sortAxis] - right[sortAxis]);
    for (let index = 1; index < group.length; index += 1) {
      const first = group[index - 1];
      const second = group[index];
      if (obstacles.some((obstacle) => segmentIntersectsObstacle(first, second, obstacle))) {
        continue;
      }
      adjacency.get(pointKey(first)).push(second);
      adjacency.get(pointKey(second)).push(first);
    }
  }
}

function findShortestRoute(start, end, pointByKey, adjacency) {
  const startKey = pointKey(start);
  const endKey = pointKey(end);
  const queue = [{ key: startKey, direction: 'START', cost: 0 }];
  const costByState = new Map([[`${startKey}|START`, 0]]);
  const previousByState = new Map();
  let finalState = null;
  while (queue.length) {
    queue.sort((left, right) => left.cost - right.cost || left.key.localeCompare(right.key));
    const current = queue.shift();
    const stateKey = `${current.key}|${current.direction}`;
    if (current.cost !== costByState.get(stateKey)) {
      continue;
    }
    if (current.key === endKey) {
      finalState = stateKey;
      break;
    }
    const currentPoint = pointByKey.get(current.key);
    for (const neighbor of adjacency.get(current.key) || []) {
      const neighborKey = pointKey(neighbor);
      const direction = currentPoint.x === neighbor.x ? 'VERTICAL' : 'HORIZONTAL';
      const distance = Math.abs(currentPoint.x - neighbor.x) + Math.abs(currentPoint.y - neighbor.y);
      const bend = current.direction === 'START' || current.direction === direction ? 0 : EDGE_BEND_COST;
      const nextCost = current.cost + distance + bend;
      const nextState = `${neighborKey}|${direction}`;
      if (nextCost >= (costByState.get(nextState) ?? Number.POSITIVE_INFINITY)) {
        continue;
      }
      costByState.set(nextState, nextCost);
      previousByState.set(nextState, stateKey);
      queue.push({ key: neighborKey, direction, cost: nextCost });
    }
  }
  if (!finalState) {
    return null;
  }
  const route = [];
  for (let state = finalState; state; state = previousByState.get(state)) {
    route.push(pointByKey.get(state.slice(0, state.lastIndexOf('|'))));
  }
  return route.reverse();
}

function compactOrthogonalPoints(points) {
  const unique = points.filter((point, index) => {
    const previous = points[index - 1];
    return !previous || previous.x !== point.x || previous.y !== point.y;
  });
  return unique.filter((point, index) => {
    const previous = unique[index - 1];
    const next = unique[index + 1];
    return !previous || !next
      || !((previous.x === point.x && point.x === next.x) || (previous.y === point.y && point.y === next.y));
  });
}

function pointInsideObstacle(point, obstacle) {
  return point.x > obstacle.left && point.x < obstacle.right
    && point.y > obstacle.top && point.y < obstacle.bottom;
}

function pointInsideOrOnRectangle(point, rectangle) {
  return point.x >= rectangle.left && point.x <= rectangle.right
    && point.y >= rectangle.top && point.y <= rectangle.bottom;
}

function segmentIntersectsObstacle(start, end, obstacle) {
  if (start.x === end.x) {
    return start.x > obstacle.left && start.x < obstacle.right
      && Math.max(start.y, end.y) > obstacle.top
      && Math.min(start.y, end.y) < obstacle.bottom;
  }
  return start.y > obstacle.top && start.y < obstacle.bottom
    && Math.max(start.x, end.x) > obstacle.left
    && Math.min(start.x, end.x) < obstacle.right;
}

function pointKey(point) {
  return `${point.x},${point.y}`;
}

export class TaskExecutionView {
  constructor(options) {
    this.document = options.document;
    this.window = options.window || this.document.defaultView || window;
    this.api = options.api;
    this.onBack = options.onBack;
    this.pollIntervalMs = Number(options.runtimeConfig?.activeJobPollIntervalMs) || 2000;
    this.disposed = false;
    this.opened = false;
    this.taskLoadSequence = 0;
    this.runLoadSequence = 0;
    this.pollTimer = null;
    this.pollInFlight = null;
    this.canvasPan = null;
    this.viewport = { x: 0, y: 0, scale: 1 };
    this.state = this.emptyState();
  }

  bind() {
    this.handlePointerMove = (event) => this.onPointerMove(event);
    this.handlePointerUp = () => this.endCanvasPan();
    this.handlePointerCancel = () => this.endCanvasPan();
    this.handleCanvasPointerDown = (event) => this.onCanvasPointerDown(event);
    this.handleCanvasWheel = (event) => this.onCanvasWheel(event);
    this.document.addEventListener('pointermove', this.handlePointerMove);
    this.document.addEventListener('pointerup', this.handlePointerUp);
    this.document.addEventListener('pointercancel', this.handlePointerCancel);
    this.byId('agentsV2ExecutionCanvas')?.addEventListener('pointerdown', this.handleCanvasPointerDown);
    this.byId('agentsV2ExecutionCanvas')?.addEventListener('wheel', this.handleCanvasWheel, { passive: false });
    this.byId('agentsV2TaskExecutionBack')?.addEventListener('click', () => this.onBack());
  }

  dispose() {
    this.disposed = true;
    this.document.removeEventListener('pointermove', this.handlePointerMove);
    this.document.removeEventListener('pointerup', this.handlePointerUp);
    this.document.removeEventListener('pointercancel', this.handlePointerCancel);
    this.byId('agentsV2ExecutionCanvas')?.removeEventListener('pointerdown', this.handleCanvasPointerDown);
    this.byId('agentsV2ExecutionCanvas')?.removeEventListener('wheel', this.handleCanvasWheel);
    this.close();
  }

  close() {
    this.opened = false;
    this.taskLoadSequence += 1;
    this.runLoadSequence += 1;
    this.stopPolling();
    this.pollInFlight = null;
    this.canvasPan = null;
    this.state = this.emptyState();
    this.viewport = { x: 0, y: 0, scale: 1 };
    this.applyViewportTransform();
    this.byId('agentsV2ExecutionCanvas')?.classList.remove('panning');
  }

  async open(taskId, project, repositories = []) {
    const taskSequence = this.taskLoadSequence + 1;
    this.taskLoadSequence = taskSequence;
    this.runLoadSequence += 1;
    this.stopPolling();
    this.pollInFlight = null;
    this.opened = true;
    this.disposed = false;
    this.state = {
      ...this.emptyState(),
      taskId,
      project,
      repositories,
      loadingTask: true
    };
    this.render();
    try {
      const task = await this.api.getProjectTask(taskId);
      if (!this.isCurrentTask(taskId, taskSequence)) {
        return;
      }
      this.state.task = task;
      this.state.loadingTask = false;
      this.state.taskError = '';
      this.render();
      const run = this.sortedRuns()[0];
      if (run?.id) {
        await this.selectRun(run.id);
      }
    } catch (error) {
      if (!this.isCurrentTask(taskId, taskSequence)) {
        return;
      }
      this.state.loadingTask = false;
      this.state.taskError = error.message || 'Task execution failed to load.';
      this.render();
    }
  }

  async selectRun(runId) {
    if (!this.state.task || !runId) {
      return;
    }
    const taskId = this.state.taskId;
    const taskSequence = this.taskLoadSequence;
    const runSequence = this.runLoadSequence + 1;
    this.runLoadSequence = runSequence;
    this.stopPolling();
    this.pollInFlight = null;
    this.state.selectedRunId = runId;
    this.state.selectedNodeRunId = null;
    this.state.selectedSourceNodeId = null;
    this.state.selectedVisualUnitKey = null;
    this.state.nodeRunSelectionMode = null;
    this.state.workflowRun = null;
    this.state.loadingRun = true;
    this.state.executionError = '';
    this.state.refreshError = '';
    this.render();
    try {
      const workflowRun = await this.api.getWorkflowRun(runId);
      if (!this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        return;
      }
      this.applyWorkflowRun(workflowRun);
      this.state.loadingRun = false;
      this.render();
      this.syncPolling();
    } catch (error) {
      if (!this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        return;
      }
      this.state.loadingRun = false;
      this.state.executionError = error.message || 'Workflow run failed to load.';
      this.render();
    }
  }

  applyWorkflowRun(workflowRun) {
    this.state.workflowRun = workflowRun;
    this.state.refreshError = '';
    this.mergeRunSummary(workflowRun);
    this.mergeTaskResult(workflowRun);
    if (this.hasRuntimeGraph(workflowRun)) {
      const projection = this.modernProjection();
      if (!projection.nodeByUnit.has(this.state.selectedVisualUnitKey)) {
        this.state.selectedVisualUnitKey = null;
        this.state.selectedSourceNodeId = null;
        this.state.selectedNodeRunId = null;
        this.state.nodeRunSelectionMode = null;
        return;
      }
      const nodeRuns = workflowRun?.nodeRuns || [];
      const selectedUnit = projection.nodeByUnit.get(this.state.selectedVisualUnitKey);
      const pinnedRunExists = nodeRuns.some((nodeRun) => nodeRun.id === this.state.selectedNodeRunId);
      if (this.state.nodeRunSelectionMode === SELECTION_PINNED_INVOCATION && pinnedRunExists) {
        return;
      }
      this.state.nodeRunSelectionMode = SELECTION_FOLLOW_LATEST;
      this.state.selectedNodeRunId = this.latestNodeRunForUnit(
        selectedUnit.sourceNodeId,
        selectedUnit.repositoryId,
        nodeRuns
      )?.id || null;
      return;
    }
    const nodeRuns = workflowRun?.nodeRuns || [];
    if (!nodeRuns.some((nodeRun) => nodeRun.id === this.state.selectedNodeRunId)) {
      this.state.selectedNodeRunId = nodeRuns[0]?.id || null;
    }
    this.state.selectedSourceNodeId = null;
    this.state.selectedVisualUnitKey = null;
  }

  mergeRunSummary(workflowRun) {
    const runs = this.state.task?.runs || [];
    this.state.task.runs = runs.map((run) => run.id === workflowRun.id ? { ...run, ...workflowRun } : run);
  }

  mergeTaskResult(workflowRun) {
    if (!this.state.task || this.sortedRuns()[0]?.id !== workflowRun.id) {
      return;
    }
    if (workflowRun.status === 'SUCCEEDED' && workflowRun.result != null) {
      this.state.task.result = workflowRun.result;
      return;
    }
    if (ACTIVE_RUN_STATUSES.has(workflowRun.status) || ['FAILED', 'CANCELLED'].includes(workflowRun.status)) {
      this.state.task.result = null;
    }
  }

  syncPolling() {
    if (this.shouldPoll()) {
      this.schedulePolling();
      return;
    }
    this.stopPolling();
  }

  schedulePolling() {
    if (this.disposed || !this.opened || this.pollTimer || this.pollInFlight) {
      return;
    }
    this.pollTimer = this.window.setTimeout(() => {
      this.pollTimer = null;
      this.pollSelectedRun();
    }, this.pollIntervalMs);
  }

  async pollSelectedRun() {
    if (this.disposed || !this.opened || !this.shouldPoll() || this.pollInFlight) {
      this.syncPolling();
      return;
    }
    const taskId = this.state.taskId;
    const taskSequence = this.taskLoadSequence;
    const runId = this.state.selectedRunId;
    const runSequence = this.runLoadSequence;
    const request = this.api.getWorkflowRun(runId);
    this.pollInFlight = request;
    try {
      const workflowRun = await request;
      if (!this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        return;
      }
      this.applyWorkflowRun(workflowRun);
      this.render();
    } catch (error) {
      if (!this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        return;
      }
      this.state.refreshError = error.message || 'Workflow run refresh failed.';
      this.render();
    } finally {
      if (this.pollInFlight === request) {
        this.pollInFlight = null;
      }
      if (!this.disposed && this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        this.syncPolling();
      }
    }
  }

  shouldPoll() {
    return Boolean(
      !this.disposed
      && this.opened
      && this.state.selectedRunId
      && ACTIVE_RUN_STATUSES.has(this.state.workflowRun?.status)
    );
  }

  render() {
    this.renderHeader();
    this.renderTaskSummary();
    this.renderHistory();
    this.renderExecutionState();
    this.renderGraph();
    this.renderNodeDetails();
  }

  renderHeader() {
    const projectName = this.state.project?.name || 'Project';
    const taskTitle = this.state.task?.title || (this.state.loadingTask ? 'Loading task...' : 'Task execution');
    this.byId('agentsV2TaskExecutionCrumbs').textContent = `Projects / ${projectName} / Tasks`;
    this.byId('agentsV2TaskExecutionTitle').textContent = taskTitle;
  }

  renderTaskSummary() {
    this.showError('agentsV2TaskExecutionTaskError', this.state.taskError);
    const summary = this.byId('agentsV2TaskExecutionSummary');
    if (this.state.loadingTask) {
      summary.innerHTML = '<div class="muted-state">Loading task...</div>';
      return;
    }
    if (!this.state.task) {
      summary.innerHTML = '';
      return;
    }
    const workflowName = this.state.workflowRun?.workflowName || this.selectedRunSummary()?.workflowName || 'Unknown workflow';
    const runStatus = this.state.workflowRun?.status || this.selectedRunSummary()?.status || 'UNKNOWN';
    const failedNodeRuns = (this.state.workflowRun?.nodeRuns || []).filter((nodeRun) => nodeRun.status === 'FAILED');
    summary.innerHTML = `
      <div class="task-execution-summary-grid">
        <div>
          <span>Task</span>
          <strong>${escapeHtml(this.state.task.input || '')}</strong>
        </div>
        <div>
          <span>Workflow</span>
          <strong>${escapeHtml(workflowName)}</strong>
        </div>
        <div>
          <span>Execution</span>
          <strong class="agents-v2-status agents-v2-status-${escapeHtml(statusTone(runStatus))}" data-run-status="${escapeHtml(runStatus)}">${escapeHtml(runStatus)}</strong>
        </div>
      </div>
      ${this.renderTaskResult(runStatus)}
      ${runStatus === 'FAILED' && failedNodeRuns.length ? this.renderRunFailureSummary(failedNodeRuns) : ''}
    `;
    summary.querySelectorAll('[data-failed-node-run-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectNodeRun(element.dataset.failedNodeRunId));
    });
  }

  renderRunFailureSummary(failedNodeRuns) {
    return `
      <div class="task-execution-failure-summary">
        <strong>Failure</strong>
        ${failedNodeRuns.map((nodeRun) => `
          <button class="task-execution-failure-row" type="button" data-failed-node-run-id="${escapeHtml(nodeRun.id)}">
            <span>${escapeHtml(nodeRun.agentName || 'Unknown agent')}</span>
            <code>${escapeHtml(nodeRun.failure?.code || 'FAILURE')}</code>
            <small>${escapeHtml(nodeRun.failure?.message || 'Node execution failed.')}</small>
          </button>
        `).join('')}
      </div>
    `;
  }

  renderTaskResult(runStatus) {
    const result = this.state.task?.result;
    const active = ACTIVE_RUN_STATUSES.has(runStatus);
    if (active) {
      return `
        <section class="task-result-section">
          <h2>Result</h2>
          <div class="muted-state compact">Result not available yet.</div>
        </section>
      `;
    }
    if (runStatus === 'SUCCEEDED' && result != null) {
      return `
        <section class="task-result-section">
          <h2>Result</h2>
          <pre>${escapeHtml(this.formatOutput(result))}</pre>
        </section>
      `;
    }
    return `
      <section class="task-result-section">
        <h2>Result</h2>
        <div class="muted-state compact">No result.</div>
      </section>
    `;
  }

  renderHistory() {
    const history = this.byId('agentsV2ExecutionHistory');
    if (this.state.loadingTask) {
      history.innerHTML = '<div class="muted-state compact">Loading executions...</div>';
      return;
    }
    if (!this.state.task) {
      history.innerHTML = '';
      return;
    }
    const runs = this.sortedRuns();
    if (!runs.length) {
      history.innerHTML = '<div class="muted-state compact">No executions yet.</div>';
      return;
    }
    history.innerHTML = runs.map((run) => `
      <button class="execution-history-row ${run.id === this.state.selectedRunId ? 'selected' : ''}" type="button" data-run-id="${escapeHtml(run.id)}">
        <span class="agents-v2-status agents-v2-status-${escapeHtml(statusTone(run.status))}" data-history-run-status="${escapeHtml(run.status || 'UNKNOWN')}">${escapeHtml(run.status || 'UNKNOWN')}</span>
        <span>${escapeHtml(this.formatDate(run.createdAt || run.startedAt || run.finishedAt))}</span>
      </button>
    `).join('');
    history.querySelectorAll('[data-run-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectRun(element.dataset.runId));
    });
  }

  renderExecutionState() {
    this.showError('agentsV2TaskExecutionError', this.state.executionError);
    this.showError('agentsV2TaskExecutionRefreshError', this.state.refreshError);
    const state = this.byId('agentsV2ExecutionState');
    if (this.state.loadingRun) {
      state.innerHTML = '<div class="muted-state compact">Loading execution...</div>';
      return;
    }
    if (!this.state.taskError && this.state.task && !this.sortedRuns().length) {
      state.innerHTML = '<div class="muted-state compact">No executions yet.</div>';
      return;
    }
    state.innerHTML = '';
  }

  renderGraph() {
    if (this.hasRuntimeGraph(this.state.workflowRun)) {
      this.renderModernGraph();
      return;
    }
    this.renderLegacyGraph();
  }

  renderModernGraph() {
    const nodesLayer = this.byId('agentsV2ExecutionNodes');
    const edgesSvg = this.byId('agentsV2ExecutionEdges');
    if (!this.state.workflowRun) {
      nodesLayer.innerHTML = '';
      edgesSvg.innerHTML = '';
      return;
    }
    const projection = this.modernProjection();
    nodesLayer.innerHTML = projection.graph.nodes.map((node) => this.renderModernNode(node, projection)).join('');
    nodesLayer.querySelectorAll('[data-execution-visual-unit-key]').forEach((element) => {
      element.addEventListener('click', () => this.selectVisualUnit(element.dataset.executionVisualUnitKey));
    });
    nodesLayer.querySelectorAll('[data-execution-run-chip-id]').forEach((element) => {
      element.addEventListener('click', (event) => {
        event.stopPropagation();
        this.selectNodeRun(element.dataset.executionRunChipId);
      });
    });
    this.syncCanvasBounds(projection.graph.nodes, false, projection);
    this.renderModernEdges(projection);
    this.applyViewportTransform();
  }

  renderModernNode(node, projection) {
    if (node.taskBoundary) {
      return this.renderTaskBoundary(node, projection);
    }
    const nodeRuns = projection.nodeRunsByUnit.get(node.visualUnitKey) || [];
    const latest = nodeRuns.at(-1) || null;
    const latestNumber = latest ? projection.invocationNumberById.get(latest.id) : null;
    const inputPorts = projection.inputPortsByNode.get(node.sourceNodeId) || [];
    const outputPorts = projection.outputPortsByNode.get(node.sourceNodeId) || [];
    const selected = node.visualUnitKey === this.state.selectedVisualUnitKey;
    const running = nodeRuns.some((nodeRun) => nodeRun.status === 'RUNNING');
    const failed = nodeRuns.some((nodeRun) => nodeRun.status === 'FAILED');
    const latestSelectedOutputId = latest?.selectedOutputPortId || null;
    const classes = [
      'execution-node',
      'execution-board-node',
      selected ? 'selected' : '',
      running ? 'execution-node-has-running' : '',
      failed ? 'execution-node-has-failed' : '',
      !nodeRuns.length ? 'execution-node-unreached' : ''
    ].filter(Boolean).join(' ');
    return `
      <article
        class="${classes}"
        data-execution-source-node-id="${escapeHtml(node.sourceNodeId)}"
        data-execution-visual-unit-key="${escapeHtml(node.visualUnitKey)}"
        data-execution-repository-id="${escapeHtml(node.repositoryId || '')}"
        data-execution-node-id="${escapeHtml(node.visualUnitKey)}"
        style="left:${Number(node.position?.x || 0)}px; top:${Number(node.position?.y || 0)}px; width:${node.layoutWidth || MODERN_NODE_WIDTH}px;"
      >
        <div class="execution-board-card-grid">
          <div class="execution-board-port-column execution-board-port-column-input">
            ${this.renderCompactPorts(inputPorts, 'input', null, node.visualUnitKey)}
          </div>
          <div class="execution-board-card-main">
            <strong>${escapeHtml(node.agentName || 'Unknown agent')}</strong>
            ${node.repositoryName ? `<small class="execution-board-repository">${escapeHtml(node.repositoryName)}</small>` : ''}
            ${latest ? `<span>#${latestNumber} ${escapeHtml(latest.status)}</span>` : ''}
            <div class="execution-board-runline">
              <small>${nodeRuns.length} ${nodeRuns.length === 1 ? 'run' : 'runs'}</small>
              ${this.renderInvocationMarkers(nodeRuns, projection)}
            </div>
          </div>
          <div class="execution-board-port-column execution-board-port-column-output">
            ${this.renderCompactPorts(outputPorts, 'output', latestSelectedOutputId, node.visualUnitKey)}
          </div>
        </div>
      </article>
    `;
  }

  renderTaskBoundary(node, projection) {
    const input = node.taskBoundary === 'INPUT';
    const ports = input
      ? projection.outputPortsByNode.get(node.sourceNodeId) || []
      : projection.inputPortsByNode.get(node.sourceNodeId) || [];
    return `
      <div
        class="execution-task-boundary execution-task-boundary-${input ? 'input' : 'output'}"
        data-execution-task-boundary="${escapeHtml(node.taskBoundary)}"
        style="left:${Number(node.position?.x || 0)}px; top:${Number(node.position?.y || 0)}px; width:${node.layoutWidth}px; height:${node.layoutHeight}px;"
      >
        ${input ? '' : this.renderCompactPorts(ports, 'input', null, node.visualUnitKey)}
        <strong>TASK ${escapeHtml(node.taskBoundary)}</strong>
        ${input ? this.renderCompactPorts(ports, 'output', null, node.visualUnitKey) : ''}
      </div>
    `;
  }

  renderCompactPorts(ports, side, selectedPortId, visualUnitKey) {
    return ports.map((port) => `
      <div
        class="execution-board-port-row execution-board-port-row-${escapeHtml(side)} ${selectedPortId === port.sourcePortId ? 'selected' : ''}"
        data-runtime-port-id="${escapeHtml(port.sourcePortId)}"
        title="${escapeHtml(port.name || 'Port')}"
      >
        <i class="execution-port-anchor" aria-hidden="true" data-runtime-port-anchor-id="${escapeHtml(portAnchorKey(visualUnitKey, port.sourcePortId))}"></i>
        <span>${escapeHtml(port.name || 'Port')}</span>
      </div>
    `).join('');
  }

  renderInvocationMarkers(nodeRuns, projection) {
    if (!nodeRuns.length) {
      return '';
    }
    const hidden = Math.max(0, nodeRuns.length - HISTORY_MARKER_LIMIT);
    const visible = nodeRuns.slice(-HISTORY_MARKER_LIMIT);
    return `
      <span class="execution-board-markers">
        ${hidden ? `<span class="execution-history-overflow">+${hidden}</span>` : ''}
        ${visible.map((nodeRun) => {
          const number = projection.invocationNumberById.get(nodeRun.id) || 1;
          const title = `#${number} ${nodeRun.status}`;
          return `<button class="execution-history-marker execution-history-marker-${escapeHtml(statusTone(nodeRun.status))} ${nodeRun.id === this.state.selectedNodeRunId ? 'selected' : ''}" type="button" title="${escapeHtml(title)}" data-execution-run-chip-id="${escapeHtml(nodeRun.id)}">${escapeHtml(statusSymbol(nodeRun.status))}</button>`;
        }).join('')}
      </span>
    `;
  }

  renderModernEdges(projection) {
    const edges = projection.graph.connections.map((connection) => {
      const sourcePort = projection.portById.get(connection.sourceOutputPortId);
      const targetPort = projection.portById.get(connection.targetInputPortId);
      const sourceNode = projection.nodeByUnit.get(connection.sourceVisualUnitKey);
      const targetNode = projection.nodeByUnit.get(connection.targetVisualUnitKey);
      if (!sourcePort || !targetPort || !sourceNode || !targetNode) {
        return '';
      }
      const start = this.modernPortPoint(sourcePort, sourceNode, projection);
      const end = this.modernPortPoint(targetPort, targetNode, projection);
      const path = this.modernPathD(start, end, sourceNode, targetNode, projection);
      const title = `${sourceNode.agentName}.${sourcePort.name} -> ${targetNode.agentName}.${targetPort.name}`;
      return `
        <g class="workflow-edge execution-edge execution-topology-edge ${connection.taskBoundary ? 'execution-task-boundary-edge' : ''}" data-runtime-connection-id="${escapeHtml(connection.sourceConnectionId)}" data-source-visual-unit-key="${escapeHtml(connection.sourceVisualUnitKey)}" data-target-visual-unit-key="${escapeHtml(connection.targetVisualUnitKey)}">
          <title>${escapeHtml(title)}</title>
          <path class="edge-visible" d="${path}" marker-end="url(#agentsV2ExecutionArrow)" />
        </g>
      `;
    }).filter(Boolean);
    this.byId('agentsV2ExecutionEdges').innerHTML = this.edgeDefs(edges.join(''));
  }

  renderLegacyGraph() {
    const nodesLayer = this.byId('agentsV2ExecutionNodes');
    const edgesSvg = this.byId('agentsV2ExecutionEdges');
    const nodeRuns = this.state.workflowRun?.nodeRuns || [];
    if (!this.state.workflowRun || !nodeRuns.length) {
      nodesLayer.innerHTML = this.state.workflowRun && !nodeRuns.length
        ? '<div class="muted-state task-execution-graph-empty">No node runs yet.</div>'
        : '';
      edgesSvg.innerHTML = '';
      return;
    }
    nodesLayer.innerHTML = nodeRuns.map((nodeRun) => this.renderNode(nodeRun)).join('');
    nodesLayer.querySelectorAll('[data-execution-node-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectNodeRun(element.dataset.executionNodeId));
    });
    this.renderEdges(nodeRuns);
    this.syncCanvasBounds(nodeRuns, true);
    this.applyViewportTransform();
  }

  renderNode(nodeRun) {
    const status = nodeRun.status || 'PENDING';
    return `
      <article
        class="execution-node execution-node-${escapeHtml(statusTone(status))} ${nodeRun.id === this.state.selectedNodeRunId ? 'selected' : ''}"
        data-execution-node-id="${escapeHtml(nodeRun.id)}"
        data-node-status="${escapeHtml(status)}"
        style="left:${Number(nodeRun.position?.x || 0)}px; top:${Number(nodeRun.position?.y || 0)}px;"
      >
        <div class="execution-node-content">
          <strong>${escapeHtml(nodeRun.agentName || 'Unknown agent')}</strong>
          <span class="agents-v2-status agents-v2-status-${escapeHtml(statusTone(status))}">${escapeHtml(status)}</span>
        </div>
      </article>
    `;
  }

  renderEdges(nodeRuns) {
    const byId = new Map(nodeRuns.map((nodeRun) => [nodeRun.id, nodeRun]));
    const edges = this.executionEdges()
      .map((edge) => {
        const source = byId.get(edge.sourceNodeRunId);
        const target = byId.get(edge.targetNodeRunId);
        if (!source || !target) {
          return '';
        }
        const start = this.nodePoint(source, 'output');
        const end = this.nodePoint(target, 'input');
        return `
          <g class="workflow-edge execution-edge" data-edge-source="${escapeHtml(source.id)}" data-edge-target="${escapeHtml(target.id)}">
            <path class="edge-visible" d="${this.pathD(start, end)}" marker-end="url(#agentsV2ExecutionArrow)" />
          </g>
        `;
      })
      .filter(Boolean);
    this.byId('agentsV2ExecutionEdges').innerHTML = this.edgeDefs(edges.join(''));
  }

  renderNodeDetails() {
    const panel = this.byId('agentsV2NodeRunDetails');
    const nodeRun = this.selectedNodeRun();
    if (!nodeRun) {
      const unit = this.state.selectedVisualUnitKey
        ? this.modernProjection().nodeByUnit.get(this.state.selectedVisualUnitKey)
        : null;
      if (unit && !unit.taskBoundary) {
        panel.innerHTML = `
          <div class="node-run-details-grid">
            ${this.detailRow('Agent', unit.agentName || 'Unknown agent')}
            ${this.detailRow('Repository', unit.repositoryName || (unit.scopeMode === 'GLOBAL' ? 'Global' : UNAVAILABLE_REPOSITORY_LABEL))}
            ${this.detailRow('Status', 'Not executed yet')}
          </div>
        `;
        return;
      }
      panel.innerHTML = '<div class="muted-state">Select a node to inspect its execution.</div>';
      return;
    }
    const failure = nodeRun.failure;
    panel.innerHTML = `
      <div class="node-run-details-grid">
        ${this.detailRow('Agent', nodeRun.agentName || 'Unknown agent')}
        ${this.detailRow('Status', nodeRun.status || 'PENDING')}
        ${this.detailRow('Input mode', this.formatInputMode(nodeRun))}
        ${this.detailRow('Instructions', nodeRun.agentInstructions || '')}
        ${this.detailRow('Started', this.formatDate(nodeRun.startedAt))}
        ${this.detailRow('Finished', this.formatDate(nodeRun.finishedAt))}
      </div>
      <section class="node-run-output">
        <h3>Output</h3>
        ${nodeRun.output == null ? '<div class="muted-state compact">No output yet.</div>' : `<pre>${escapeHtml(this.formatOutput(nodeRun.output))}</pre>`}
      </section>
      ${failure ? `
        <section class="node-run-failure">
          <h3>Failure</h3>
          <strong>${escapeHtml(failure.code || 'FAILURE')}</strong>
          <p>${escapeHtml(failure.message || '')}</p>
        </section>
      ` : ''}
    `;
  }

  detailRow(label, value) {
    return `
      <div class="node-run-detail-row">
        <span>${escapeHtml(label)}</span>
        <strong>${escapeHtml(value || '-')}</strong>
      </div>
    `;
  }

  modernProjection() {
    const runtimeGraph = this.state.workflowRun?.runtimeGraph || { nodes: [], ports: [], connections: [] };
    const graph = buildExecutionProjection(
      runtimeGraph,
      this.state.workflowRun?.repositoryIds || [],
      this.state.repositories || []
    );
    const nodeRuns = this.sortedNodeRuns(this.state.workflowRun?.nodeRuns || []);
    const nodeByUnit = new Map((graph.nodes || []).map((node) => [node.visualUnitKey, node]));
    const portById = new Map((graph.ports || []).map((port) => [port.sourcePortId, port]));
    const inputPortsByNode = this.groupPorts(graph.ports || [], 'INPUT');
    const outputPortsByNode = this.groupPorts(graph.ports || [], 'OUTPUT');
    const nodeRunsByUnit = new Map();
    const invocationNumberById = new Map();
    for (const nodeRun of nodeRuns) {
      const key = visualUnitKey(nodeRun.sourceNodeId, nodeRun.repositoryId);
      if (!nodeRunsByUnit.has(key)) {
        nodeRunsByUnit.set(key, []);
      }
      const runs = nodeRunsByUnit.get(key);
      runs.push(nodeRun);
      invocationNumberById.set(nodeRun.id, runs.length);
    }
    return {
      graph,
      nodeRuns,
      nodeByUnit,
      portById,
      inputPortsByNode,
      outputPortsByNode,
      nodeRunsByUnit,
      invocationNumberById
    };
  }

  groupPorts(ports, direction) {
    const grouped = new Map();
    for (const port of ports.filter((item) => item.direction === direction)) {
      if (!grouped.has(port.sourceNodeId)) {
        grouped.set(port.sourceNodeId, []);
      }
      grouped.get(port.sourceNodeId).push(port);
    }
    for (const list of grouped.values()) {
      list.sort((left, right) => (left.order || 0) - (right.order || 0));
    }
    return grouped;
  }

  formatInputMode(nodeRun) {
    if (this.incomingExecutionEdges(nodeRun.id).length === 0) {
      return 'Original task';
    }
    if (nodeRun.inputMode === 'TASK_AND_DEPENDENCIES') {
      return 'Original task + previous outputs';
    }
    if (nodeRun.inputMode === 'DEPENDENCIES_ONLY') {
      return 'Previous outputs only';
    }
    return 'Unknown';
  }

  consumedConnectionResolutions(nodeRunId = null) {
    return (this.state.workflowRun?.connectionResolutions || [])
      .filter((resolution) => resolution.resolutionType === 'DELIVERED')
      .filter((resolution) => Boolean(resolution.consumedByNodeRunId))
      .filter((resolution) => !nodeRunId || resolution.consumedByNodeRunId === nodeRunId);
  }

  executionEdges() {
    const resolutionEdges = this.consumedConnectionResolutions()
      .map((resolution) => ({
        sourceNodeRunId: resolution.sourceNodeRunId,
        targetNodeRunId: resolution.consumedByNodeRunId
      }));
    if (resolutionEdges.length) {
      return resolutionEdges;
    }
    return this.state.workflowRun?.executionEdges || [];
  }

  incomingExecutionEdges(nodeRunId) {
    return this.executionEdges().filter((edge) => edge.targetNodeRunId === nodeRunId);
  }

  selectedNodeRun() {
    return (this.state.workflowRun?.nodeRuns || []).find((nodeRun) => nodeRun.id === this.state.selectedNodeRunId) || null;
  }

  selectVisualUnit(unitKey) {
    const unit = this.modernProjection().nodeByUnit.get(unitKey);
    if (!unit || unit.taskBoundary) {
      return;
    }
    this.state.selectedVisualUnitKey = unit.visualUnitKey;
    this.state.selectedSourceNodeId = unit.sourceNodeId;
    this.state.nodeRunSelectionMode = SELECTION_FOLLOW_LATEST;
    this.state.selectedNodeRunId = this.latestNodeRunForUnit(
      unit.sourceNodeId,
      unit.repositoryId,
      this.state.workflowRun?.nodeRuns || []
    )?.id || null;
    this.renderGraph();
    this.renderNodeDetails();
  }

  selectNodeRun(nodeRunId) {
    const nodeRun = (this.state.workflowRun?.nodeRuns || []).find((item) => item.id === nodeRunId);
    if (nodeRun) {
      this.state.selectedSourceNodeId = nodeRun.sourceNodeId;
      this.state.selectedVisualUnitKey = visualUnitKey(nodeRun.sourceNodeId, nodeRun.repositoryId);
      this.state.nodeRunSelectionMode = SELECTION_PINNED_INVOCATION;
    }
    this.state.selectedNodeRunId = nodeRunId;
    this.renderGraph();
    this.renderNodeDetails();
  }

  selectedRunSummary() {
    return (this.state.task?.runs || []).find((run) => run.id === this.state.selectedRunId) || null;
  }

  sortedRuns() {
    return (this.state.task?.runs || [])
      .map((run, index) => ({ run, index }))
      .sort((left, right) => {
        const leftTime = this.runTime(left.run);
        const rightTime = this.runTime(right.run);
        if (leftTime !== rightTime) {
          return rightTime - leftTime;
        }
        return left.index - right.index;
      })
      .map((entry) => entry.run);
  }

  runTime(run) {
    const value = run.createdAt || run.startedAt || run.finishedAt || run.updatedAt;
    const time = value ? new Date(value).getTime() : Number.NaN;
    return Number.isNaN(time) ? 0 : time;
  }

  sortedNodeRuns(nodeRuns) {
    return nodeRuns.slice().sort((left, right) => {
      const leftTime = this.parseTime(left.createdAt);
      const rightTime = this.parseTime(right.createdAt);
      if (leftTime !== rightTime) {
        return leftTime - rightTime;
      }
      return String(left.id || '').localeCompare(String(right.id || ''));
    });
  }

  latestNodeRunForUnit(sourceNodeId, repositoryId, nodeRuns) {
    return this.sortedNodeRuns(nodeRuns.filter((nodeRun) => nodeRun.sourceNodeId === sourceNodeId
      && normalizedRepositoryId(nodeRun.repositoryId) === normalizedRepositoryId(repositoryId))).at(-1) || null;
  }

  hasRuntimeGraph(workflowRun) {
    return Boolean(workflowRun?.runtimeGraph && Array.isArray(workflowRun.runtimeGraph.nodes));
  }

  parseTime(value) {
    const time = value ? new Date(value).getTime() : Number.NaN;
    return Number.isNaN(time) ? 0 : time;
  }

  formatOutput(value) {
    if (typeof value === 'string') {
      try {
        return JSON.stringify(JSON.parse(value), null, 2);
      } catch (_) {
        return value;
      }
    }
    try {
      return JSON.stringify(value, null, 2);
    } catch (_) {
      return String(value);
    }
  }

  formatDate(value) {
    if (!value) {
      return '-';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return date.toLocaleString();
  }

  nodePoint(nodeRun, kind) {
    return {
      x: Number(nodeRun.position?.x || 0) + (kind === 'output' ? NODE_WIDTH : 0),
      y: Number(nodeRun.position?.y || 0) + NODE_MID_Y
    };
  }

  modernPortPoint(port, node, projection) {
    const measured = this.elementCanvasCenter(this.elementByData(
      'data-runtime-port-anchor-id',
      portAnchorKey(node.visualUnitKey, port.sourcePortId)
    ));
    if (measured) {
      return measured;
    }
    if (!node) {
      return { x: 0, y: 0 };
    }
    const ports = port.direction === 'OUTPUT'
      ? projection.outputPortsByNode.get(port.sourceNodeId) || []
      : projection.inputPortsByNode.get(port.sourceNodeId) || [];
    const index = Math.max(0, ports.findIndex((item) => item.sourcePortId === port.sourcePortId));
    return {
      x: Number(node.position?.x || 0) + (port.direction === 'OUTPUT' ? (node.layoutWidth || MODERN_NODE_WIDTH) : 0),
      y: Number(node.position?.y || 0) + 42 + (index * MODERN_PORT_ROW_HEIGHT)
    };
  }

  pathD(start, end) {
    const mid = Math.max(40, Math.abs(end.x - start.x) / 2);
    return `M ${start.x} ${start.y} C ${start.x + mid} ${start.y}, ${end.x - mid} ${end.y}, ${end.x} ${end.y}`;
  }

  modernPathD(start, end, sourceNode, targetNode, projection = null) {
    const bounds = (projection?.graph.nodes || [sourceNode, targetNode])
      .map((node) => this.modernNodeBounds(node, projection));
    return this.orthogonalRoundedPath(routeExecutionEdge(start, end, bounds));
  }

  orthogonalRoundedPath(points) {
    const clean = points.filter((point, index) => {
      const previous = points[index - 1];
      return !previous || previous.x !== point.x || previous.y !== point.y;
    });
    if (!clean.length) {
      return '';
    }
    let d = `M ${clean[0].x} ${clean[0].y}`;
    for (let index = 1; index < clean.length; index += 1) {
      const current = clean[index];
      const previous = clean[index - 1];
      const next = clean[index + 1];
      if (!next) {
        d += this.orthogonalLineCommand(previous, current);
        break;
      }
      const incomingHorizontal = previous.y === current.y;
      const outgoingHorizontal = current.y === next.y;
      if (incomingHorizontal === outgoingHorizontal) {
        d += this.orthogonalLineCommand(previous, current);
        continue;
      }
      const incomingDistance = incomingHorizontal ? Math.abs(current.x - previous.x) : Math.abs(current.y - previous.y);
      const outgoingDistance = outgoingHorizontal ? Math.abs(next.x - current.x) : Math.abs(next.y - current.y);
      const radius = Math.min(12, incomingDistance / 2, outgoingDistance / 2);
      if (radius <= 0) {
        d += this.orthogonalLineCommand(previous, current);
        continue;
      }
      const before = incomingHorizontal
        ? { x: current.x - Math.sign(current.x - previous.x) * radius, y: current.y }
        : { x: current.x, y: current.y - Math.sign(current.y - previous.y) * radius };
      const after = outgoingHorizontal
        ? { x: current.x + Math.sign(next.x - current.x) * radius, y: current.y }
        : { x: current.x, y: current.y + Math.sign(next.y - current.y) * radius };
      d += this.orthogonalLineCommand(previous, before);
      d += ` Q ${current.x} ${current.y} ${after.x} ${after.y}`;
    }
    return d;
  }

  orthogonalLineCommand(from, to) {
    if (from.x === to.x && from.y === to.y) {
      return '';
    }
    if (from.y === to.y) {
      return ` H ${to.x}`;
    }
    if (from.x === to.x) {
      return ` V ${to.y}`;
    }
    return ` H ${to.x} V ${to.y}`;
  }

  edgeDefs(content) {
    return `
      <defs>
        <marker id="agentsV2ExecutionArrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z"></path>
        </marker>
      </defs>
      ${content}
    `;
  }

  elementByData(attribute, value) {
    if (!value) {
      return null;
    }
    return [...this.document.querySelectorAll(`[${attribute}]`)]
      .find((element) => element.getAttribute(attribute) === value) || null;
  }

  elementCanvasBounds(element) {
    if (!element?.getBoundingClientRect) {
      return null;
    }
    const rect = element.getBoundingClientRect();
    const width = Number(rect.width);
    const height = Number(rect.height);
    if (!Number.isFinite(width) || !Number.isFinite(height) || (width === 0 && height === 0)) {
      return null;
    }
    const canvasRect = this.byId('agentsV2ExecutionCanvas')?.getBoundingClientRect?.() || { left: 0, top: 0 };
    const scale = this.viewport.scale || 1;
    const left = ((Number(rect.left) - Number(canvasRect.left || 0)) - this.viewport.x) / scale;
    const top = ((Number(rect.top) - Number(canvasRect.top || 0)) - this.viewport.y) / scale;
    return {
      left,
      top,
      right: left + (width / scale),
      bottom: top + (height / scale),
      width: width / scale,
      height: height / scale
    };
  }

  elementCanvasCenter(element) {
    const bounds = this.elementCanvasBounds(element);
    if (!bounds) {
      return null;
    }
    return {
      x: bounds.left + (bounds.width / 2),
      y: bounds.top + (bounds.height / 2)
    };
  }

  modernNodeBounds(node, projection = null) {
    const measured = this.elementCanvasBounds(node.taskBoundary
      ? this.elementByData('data-execution-task-boundary', node.taskBoundary)
      : this.elementByData('data-execution-visual-unit-key', node.visualUnitKey));
    if (measured) {
      return measured;
    }
    const left = Number(node.position?.x || 0);
    const top = Number(node.position?.y || 0);
    const height = this.modernNodeHeight(node, projection);
    const width = node.layoutWidth || MODERN_NODE_WIDTH;
    return {
      left,
      top,
      right: left + width,
      bottom: top + height,
      width,
      height
    };
  }

  onCanvasPointerDown(event) {
    if (event.button !== 0) {
      return;
    }
    if (event.target?.closest?.('.execution-node, button, select, input, textarea')) {
      return;
    }
    event.preventDefault();
    this.canvasPan = {
      startX: event.clientX,
      startY: event.clientY,
      originalX: this.viewport.x,
      originalY: this.viewport.y
    };
    this.byId('agentsV2ExecutionCanvas')?.classList.add('panning');
  }

  onPointerMove(event) {
    if (!this.canvasPan) {
      return;
    }
    this.viewport = {
      ...this.viewport,
      x: this.canvasPan.originalX + (event.clientX - this.canvasPan.startX),
      y: this.canvasPan.originalY + (event.clientY - this.canvasPan.startY)
    };
    this.applyViewportTransform();
  }

  endCanvasPan() {
    this.canvasPan = null;
    this.byId('agentsV2ExecutionCanvas')?.classList.remove('panning');
  }

  onCanvasWheel(event) {
    if (!this.state.workflowRun) {
      return;
    }
    event.preventDefault();
    const canvas = this.byId('agentsV2ExecutionCanvas');
    if (!canvas) {
      return;
    }
    const canvasRect = canvas.getBoundingClientRect();
    const before = this.canvasPoint(event);
    const zoomFactor = event.deltaY < 0 ? 1.08 : 0.92;
    const scale = clamp(this.viewport.scale * zoomFactor, MIN_CANVAS_SCALE, MAX_CANVAS_SCALE);
    this.viewport = {
      scale,
      x: (event.clientX - canvasRect.left) - (before.x * scale),
      y: (event.clientY - canvasRect.top) - (before.y * scale)
    };
    this.applyViewportTransform();
  }

  canvasPoint(event) {
    const canvas = this.byId('agentsV2ExecutionCanvas');
    const rect = canvas?.getBoundingClientRect?.() || { left: 0, top: 0 };
    return {
      x: ((event.clientX - rect.left) - this.viewport.x) / this.viewport.scale,
      y: ((event.clientY - rect.top) - this.viewport.y) / this.viewport.scale
    };
  }

  syncCanvasBounds(nodes, legacy = true, projection = null) {
    const edgesSvg = this.byId('agentsV2ExecutionEdges');
    const nodesLayer = this.byId('agentsV2ExecutionNodes');
    let width = MIN_CANVAS_WIDTH;
    let height = MIN_CANVAS_HEIGHT;
    for (const node of nodes) {
      if (legacy) {
        width = Math.max(width, Number(node.position?.x || 0) + NODE_WIDTH + CANVAS_PADDING);
        height = Math.max(height, Number(node.position?.y || 0) + NODE_HEIGHT + CANVAS_PADDING);
        continue;
      }
      const measured = this.elementCanvasBounds(node.taskBoundary
        ? this.elementByData('data-execution-task-boundary', node.taskBoundary)
        : this.elementByData('data-execution-visual-unit-key', node.visualUnitKey));
      const nodeHeight = measured?.height || this.modernNodeHeight(node, projection);
      width = Math.max(width, Number(node.position?.x || 0) + (node.layoutWidth || MODERN_NODE_WIDTH) + CANVAS_PADDING);
      height = Math.max(height, Number(node.position?.y || 0) + nodeHeight + CANVAS_PADDING);
    }
    const widthValue = `${Math.ceil(width)}px`;
    const heightValue = `${Math.ceil(height)}px`;
    edgesSvg.style.width = widthValue;
    edgesSvg.style.height = heightValue;
    edgesSvg.setAttribute('width', String(Math.ceil(width)));
    edgesSvg.setAttribute('height', String(Math.ceil(height)));
    nodesLayer.style.width = widthValue;
    nodesLayer.style.height = heightValue;
  }

  modernNodeHeight(node, projection) {
    if (node.layoutHeight) {
      return node.layoutHeight;
    }
    const inputCount = (projection?.inputPortsByNode.get(node.sourceNodeId) || []).length;
    const outputCount = (projection?.outputPortsByNode.get(node.sourceNodeId) || []).length;
    const portRows = Math.max(inputCount, outputCount);
    return Math.max(MODERN_NODE_FALLBACK_HEIGHT, 58 + (portRows * MODERN_PORT_ROW_HEIGHT));
  }

  applyViewportTransform() {
    const transform = `translate(${this.viewport.x}px, ${this.viewport.y}px) scale(${this.viewport.scale})`;
    for (const element of [this.byId('agentsV2ExecutionEdges'), this.byId('agentsV2ExecutionNodes')]) {
      if (!element) {
        continue;
      }
      element.style.transform = transform;
      element.style.transformOrigin = '0 0';
    }
  }

  stopPolling() {
    if (this.pollTimer) {
      this.window.clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  isCurrentTask(taskId, taskSequence) {
    return !this.disposed
      && this.opened
      && this.state.taskId === taskId
      && this.taskLoadSequence === taskSequence;
  }

  isCurrentRun(taskId, taskSequence, runId, runSequence) {
    return this.isCurrentTask(taskId, taskSequence)
      && this.state.selectedRunId === runId
      && this.runLoadSequence === runSequence;
  }

  showError(id, message) {
    const element = this.byId(id);
    element.textContent = message || '';
    element.classList.toggle('hidden', !message);
  }

  byId(id) {
    return this.document.getElementById(id);
  }

  emptyState() {
    return {
      taskId: null,
      project: null,
      task: null,
      workflowRun: null,
      selectedRunId: null,
      selectedNodeRunId: null,
      selectedSourceNodeId: null,
      selectedVisualUnitKey: null,
      nodeRunSelectionMode: null,
      repositories: [],
      loadingTask: false,
      loadingRun: false,
      taskError: '',
      executionError: '',
      refreshError: ''
    };
  }
}

export function statusTone(status) {
  const normalized = String(status || '').toLowerCase();
  if (normalized === 'queued') {
    return 'queued';
  }
  if (normalized === 'running') {
    return 'running';
  }
  if (normalized === 'succeeded') {
    return 'succeeded';
  }
  if (normalized === 'failed') {
    return 'failed';
  }
  if (normalized === 'blocked') {
    return 'blocked';
  }
  if (normalized === 'cancelled') {
    return 'cancelled';
  }
  if (normalized === 'pending') {
    return 'pending';
  }
  return 'unknown';
}

function statusSymbol(status) {
  if (status === 'SUCCEEDED') {
    return '✓';
  }
  if (status === 'RUNNING') {
    return '●';
  }
  if (status === 'PENDING') {
    return '○';
  }
  if (status === 'FAILED') {
    return '!';
  }
  if (status === 'BLOCKED') {
    return 'B';
  }
  if (status === 'CANCELLED') {
    return '×';
  }
  return '?';
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
