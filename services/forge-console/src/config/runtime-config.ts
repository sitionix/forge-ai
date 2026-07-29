export type ApiMode = 'same-origin';

export interface OperatorRuntimeConfig {
  apiMode: ApiMode;
  operatorUiApiBasePath: string;
  operatorApiBasePath: string;
  infrastructureApiBasePath: string;
  statusPollIntervalMs: number;
  activeJobPollIntervalMs: number;
  graphPollIntervalMs: number;
  jarvisQueryIncludeTests: boolean;
  jarvisQueryMaxFlows: number | null;
}

declare global {
  interface Window {
    FORGE_OPERATOR_RUNTIME_CONFIG?: Partial<OperatorRuntimeConfig>;
  }
}

export const defaultRuntimeConfig: OperatorRuntimeConfig = {
  apiMode: 'same-origin',
  operatorUiApiBasePath: '/api/v1/forge-ai/operator/ui',
  operatorApiBasePath: '/api/v1/forge-ai/operator',
  infrastructureApiBasePath: '/api/v1/infrastructure',
  statusPollIntervalMs: 15000,
  activeJobPollIntervalMs: 1500,
  graphPollIntervalMs: 30000,
  jarvisQueryIncludeTests: false,
  jarvisQueryMaxFlows: null
};

export function contextPathFromLocation(pathname: string = window.location.pathname): string {
  const marker = '/operator/';
  return pathname.includes(marker) ? pathname.slice(0, pathname.indexOf(marker)) : '';
}

export function runtimeConfigFromWindow(source: Partial<OperatorRuntimeConfig> | undefined = window.FORGE_OPERATOR_RUNTIME_CONFIG): OperatorRuntimeConfig {
  return normalizeRuntimeConfig({ ...defaultRuntimeConfig, ...(source ?? {}) });
}

export async function loadRuntimeConfig(fetcher: typeof fetch = fetch): Promise<OperatorRuntimeConfig> {
  try {
    const response = await fetcher('./runtime-config.json', { cache: 'no-store' });
    if (!response.ok) {
      return runtimeConfigFromWindow();
    }
    const data = (await response.json()) as Partial<OperatorRuntimeConfig>;
    return normalizeRuntimeConfig({ ...runtimeConfigFromWindow(), ...data });
  } catch {
    return runtimeConfigFromWindow();
  }
}

function normalizeRuntimeConfig(config: OperatorRuntimeConfig): OperatorRuntimeConfig {
  return {
    apiMode: 'same-origin',
    operatorUiApiBasePath: normalizeBasePath(config.operatorUiApiBasePath),
    operatorApiBasePath: normalizeBasePath(config.operatorApiBasePath),
    infrastructureApiBasePath: normalizeBasePath(config.infrastructureApiBasePath),
    statusPollIntervalMs: positiveInteger(config.statusPollIntervalMs, defaultRuntimeConfig.statusPollIntervalMs),
    activeJobPollIntervalMs: positiveInteger(config.activeJobPollIntervalMs, defaultRuntimeConfig.activeJobPollIntervalMs),
    graphPollIntervalMs: positiveInteger(config.graphPollIntervalMs, defaultRuntimeConfig.graphPollIntervalMs),
    jarvisQueryIncludeTests: Boolean(config.jarvisQueryIncludeTests),
    jarvisQueryMaxFlows: optionalPositiveInteger(config.jarvisQueryMaxFlows)
  };
}

function normalizeBasePath(value: string): string {
  const trimmed = value.trim();
  const absolute = trimmed.startsWith('/') ? trimmed : `/${trimmed}`;
  return absolute.replace(/\/$/, '');
}

function positiveInteger(value: number, fallback: number): number {
  return Number.isInteger(value) && value > 0 ? value : fallback;
}

function optionalPositiveInteger(value: number | null | undefined): number | null {
  return Number.isInteger(value) && Number(value) > 0 ? Number(value) : null;
}
