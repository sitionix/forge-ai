import { cp, mkdir, readFile, stat, unlink, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { parse } from 'yaml';

const root = resolve(import.meta.dirname, '..');
const repoRoot = resolve(root, '..', '..');
const dist = resolve(root, 'dist');
const defaults = {
  apiMode: 'same-origin',
  infrastructureApiBasePath: '/api/v1/infrastructure',
  statusPollIntervalMs: 15000,
  activeJobPollIntervalMs: 1500,
  graphPollIntervalMs: 30000,
  graphCacheEnabled: true,
  graphCacheMaxRevisions: 3,
  graphCacheMaxAgeSeconds: 86400,
  graphFetchConcurrency: 2,
  graphNodePageSize: 500,
  graphEdgePageSize: 1000,
  graphFitPaddingPx: 40,
  graphFitZoomAllowance: 0.85,
  graphZoomSensitivity: 1,
  graphNodeLabelZoomThreshold: 0.7,
  graphEdgeLabelZoomThreshold: 1.4,
  graphLayoutWorkerEnabled: true,
  graphTablePageSize: 120
};

await mkdir(resolve(dist, 'operator'), { recursive: true });
await cp(resolve(root, 'src', 'operator'), resolve(dist, 'operator'), {
  recursive: true,
  force: true
});
await removeEmptyViteEntry();

const runtimeConfig = await loadRuntimeConfigFromRoot();
const runtimeJson = `${JSON.stringify(runtimeConfig, null, 2)}\n`;
await writeFile(resolve(dist, 'operator', 'runtime-config.json'), runtimeJson, 'utf8');
await writeFile(
  resolve(dist, 'operator', 'runtime-config.js'),
  `window.FORGE_OPERATOR_RUNTIME_CONFIG = Object.freeze(${runtimeJson.trim()});\n`,
  'utf8'
);

async function loadRuntimeConfigFromRoot() {
  const configPath = resolve(repoRoot, 'config', 'forge-ai.yaml');
  const data = parse(await readFile(configPath, 'utf8'));
  const consoleConfig = data?.forge?.ai?.services?.console ?? {};
  return {
    ...defaults,
    apiMode: stringValue(consoleConfig, 'api-mode', 'apiMode') ?? defaults.apiMode,
    statusPollIntervalMs: positiveInteger(consoleConfig, 'status-poll-interval-ms', 'statusPollIntervalMs', defaults.statusPollIntervalMs),
    activeJobPollIntervalMs: positiveInteger(
      consoleConfig,
      'active-job-poll-interval-ms',
      'activeJobPollIntervalMs',
      defaults.activeJobPollIntervalMs
    ),
    graphPollIntervalMs: positiveInteger(consoleConfig, 'graph-poll-interval-ms', 'graphPollIntervalMs', defaults.graphPollIntervalMs),
    graphCacheEnabled: booleanValue(consoleConfig, 'graph-cache-enabled', 'graphCacheEnabled', defaults.graphCacheEnabled),
    graphCacheMaxRevisions: positiveInteger(consoleConfig, 'graph-cache-max-revisions', 'graphCacheMaxRevisions', defaults.graphCacheMaxRevisions),
    graphCacheMaxAgeSeconds: positiveInteger(consoleConfig, 'graph-cache-max-age-seconds', 'graphCacheMaxAgeSeconds', defaults.graphCacheMaxAgeSeconds),
    graphFetchConcurrency: positiveInteger(consoleConfig, 'graph-fetch-concurrency', 'graphFetchConcurrency', defaults.graphFetchConcurrency),
    graphNodePageSize: positiveInteger(consoleConfig, 'graph-node-page-size', 'graphNodePageSize', defaults.graphNodePageSize),
    graphEdgePageSize: positiveInteger(consoleConfig, 'graph-edge-page-size', 'graphEdgePageSize', defaults.graphEdgePageSize),
    graphFitPaddingPx: positiveInteger(consoleConfig, 'graph-fit-padding-px', 'graphFitPaddingPx', defaults.graphFitPaddingPx),
    graphFitZoomAllowance: positiveNumber(consoleConfig, 'graph-fit-zoom-allowance', 'graphFitZoomAllowance', defaults.graphFitZoomAllowance),
    graphZoomSensitivity: positiveNumber(consoleConfig, 'graph-zoom-sensitivity', 'graphZoomSensitivity', defaults.graphZoomSensitivity),
    graphNodeLabelZoomThreshold: positiveNumber(consoleConfig, 'graph-node-label-zoom-threshold', 'graphNodeLabelZoomThreshold', defaults.graphNodeLabelZoomThreshold),
    graphEdgeLabelZoomThreshold: positiveNumber(consoleConfig, 'graph-edge-label-zoom-threshold', 'graphEdgeLabelZoomThreshold', defaults.graphEdgeLabelZoomThreshold),
    graphLayoutWorkerEnabled: booleanValue(consoleConfig, 'graph-layout-worker-enabled', 'graphLayoutWorkerEnabled', defaults.graphLayoutWorkerEnabled),
    graphTablePageSize: positiveInteger(consoleConfig, 'graph-table-page-size', 'graphTablePageSize', defaults.graphTablePageSize)
  };
}

function stringValue(source, kebabName, camelName) {
  const value = source[kebabName] ?? source[camelName];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function positiveInteger(source, kebabName, camelName, fallback) {
  const value = Number(source[kebabName] ?? source[camelName]);
  return Number.isInteger(value) && value > 0 ? value : fallback;
}

function positiveNumber(source, kebabName, camelName, fallback) {
  const value = Number(source[kebabName] ?? source[camelName]);
  return Number.isFinite(value) && value > 0 ? value : fallback;
}

function booleanValue(source, kebabName, camelName, fallback) {
  const value = source[kebabName] ?? source[camelName];
  return typeof value === 'boolean' ? value : fallback;
}

async function removeEmptyViteEntry() {
  const entryPath = resolve(dist, 'assets', 'console.js');
  const sourceMapPath = resolve(dist, 'assets', 'console.js.map');
  try {
    const entry = await stat(entryPath);
    if (entry.size === 0) {
      await unlink(entryPath);
      await unlink(sourceMapPath).catch(() => undefined);
    }
  } catch (error) {
    if (error?.code !== 'ENOENT') {
      throw error;
    }
  }
}
