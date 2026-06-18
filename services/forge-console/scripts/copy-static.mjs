import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { parse } from 'yaml';

const root = resolve(import.meta.dirname, '..');
const repoRoot = resolve(root, '..', '..');
const dist = resolve(root, 'dist');
const defaults = {
  apiMode: 'same-origin',
  operatorUiApiBasePath: '/api/v1/forge-ai/operator/ui',
  operatorApiBasePath: '/api/v1/forge-ai/operator',
  infrastructureApiBasePath: '/api/v1/infrastructure',
  statusPollIntervalMs: 15000,
  activeJobPollIntervalMs: 1500,
  graphPollIntervalMs: 30000
};

await mkdir(resolve(dist, 'operator'), { recursive: true });
await cp(resolve(root, 'src', 'operator'), resolve(dist, 'operator'), {
  recursive: true,
  force: true
});

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
    graphPollIntervalMs: positiveInteger(consoleConfig, 'graph-poll-interval-ms', 'graphPollIntervalMs', defaults.graphPollIntervalMs)
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
