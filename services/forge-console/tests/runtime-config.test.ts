import { describe, expect, it } from 'vitest';
import { contextPathFromLocation, runtimeConfigFromWindow } from '../src/config/runtime-config';

describe('runtime config', () => {
  it('uses same-origin context path from operator URLs', () => {
    expect(contextPathFromLocation('/fgaisox/operator/index.html')).toBe('/fgaisox');
    expect(contextPathFromLocation('/operator/index.html')).toBe('');
  });

  it('normalizes poll intervals and base paths', () => {
    const config = runtimeConfigFromWindow({
      operatorUiApiBasePath: 'api/ui/',
      activeJobPollIntervalMs: 0,
      statusPollIntervalMs: 25000
    });

    expect(config.operatorUiApiBasePath).toBe('/api/ui');
    expect(config.activeJobPollIntervalMs).toBe(1500);
    expect(config.statusPollIntervalMs).toBe(25000);
    expect(config.apiMode).toBe('same-origin');
  });
});
