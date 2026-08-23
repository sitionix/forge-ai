import { describe, expect, it } from 'vitest';
import { contextPathFromLocation, runtimeConfigFromWindow } from '../src/config/runtime-config';

describe('runtime config', () => {
  it('uses same-origin context path from operator URLs', () => {
    expect(contextPathFromLocation('/fgaisox/operator/index.html')).toBe('/fgaisox');
    expect(contextPathFromLocation('/operator/index.html')).toBe('');
  });

  it('normalizes poll intervals and base paths', () => {
    const config = runtimeConfigFromWindow({
      infrastructureApiBasePath: 'api/infrastructure/',
      activeJobPollIntervalMs: 0,
      statusPollIntervalMs: 25000
    });

    expect(config.infrastructureApiBasePath).toBe('/api/infrastructure');
    expect(config.activeJobPollIntervalMs).toBe(1500);
    expect(config.statusPollIntervalMs).toBe(25000);
    expect(config.apiMode).toBe('same-origin');
    expect(config.jarvisQueryIncludeTests).toBe(false);
    expect(config.jarvisQueryMaxFlows).toBeNull();
  });

  it('normalizes Jarvis human query defaults', () => {
    const config = runtimeConfigFromWindow({
      jarvisQueryIncludeTests: true,
      jarvisQueryMaxFlows: 3
    });

    expect(config.jarvisQueryIncludeTests).toBe(true);
    expect(config.jarvisQueryMaxFlows).toBe(3);
  });
});
