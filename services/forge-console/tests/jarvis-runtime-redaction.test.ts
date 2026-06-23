import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { JarvisPage } from '../src/operator/jarvis-page.js';

function jarvisDom() {
  return new JSDOM(`<!doctype html>
    <body data-page="jarvis">
      <button id="refreshJarvis"></button>
      <span id="jarvisUpdated"></span>
      <div id="jarvisStatusError" class="hidden"></div>
      <div id="jarvisActionsError" class="hidden"></div>
      <section id="jarvisStatusCards"></section>
      <section id="jarvisActions"></section>
      <form id="jarvisCommandForm">
        <textarea id="jarvisCommandText"></textarea>
        <button id="executeJarvisCommand" type="submit">Execute</button>
      </form>
      <div id="jarvisCommandError" class="hidden"></div>
      <section id="jarvisCommandResult" class="hidden"></section>
      <form id="jarvisChatForm">
        <textarea id="jarvisChatMessage"></textarea>
        <input id="jarvisChatMaxContext">
        <button id="sendJarvisChat" type="submit">Send</button>
      </form>
      <div id="jarvisChatError" class="hidden"></div>
      <section id="jarvisChatAnswer" class="hidden"></section>
      <section id="jarvisChatContext" class="hidden"></section>
      <section id="jarvisChatDiagnostics" class="hidden"></section>
    </body>`, { url: 'http://127.0.0.1/operator/jarvis.html' });
}

async function flushAsync() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('Jarvis runtime rendering', () => {
  it('PERF-CON-06 renders Jarvis responses without source content or command arrays', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path.endsWith('/status') ? { status: 'UP', model: {}, ollama: {}, actions: { count: 1 } } : { actions: [] })),
      post: vi.fn((path: string) => {
        if (path.endsWith('/command')) {
          return Promise.resolve({
            intent: { action: 'safe', target: 'run', arguments: {} },
            execution: { executed: true, message: 'Action executed: safe.run', output: 'done' }
          });
        }
        return Promise.resolve({
          answer: 'Answer without private context',
          usedContext: [{
            sourceId: 'forge-ai',
            relativePath: 'src/JarvisGateway.java',
            lineStart: 1,
            lineEnd: 3,
            score: 1,
            reason: 'Matched JarvisGateway',
            content: 'SECRET_SOURCE_CONTENT'
          }],
          diagnostics: [{ code: 'OK', message: 'No sensitive data' }]
        });
      })
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();

    (dom.window.document.getElementById('jarvisChatMessage') as HTMLTextAreaElement).value = 'explain';
    await page.submitChat({ preventDefault: () => undefined });
    (dom.window.document.getElementById('jarvisCommandText') as HTMLTextAreaElement).value = 'run';
    await page.submitCommand({ preventDefault: () => undefined });

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('Answer without private context');
    expect(text).toContain('src/JarvisGateway.java');
    expect(text).not.toContain('SECRET_SOURCE_CONTENT');
    expect(text).not.toContain('["bash"');
    expect(text).not.toContain('sleep 0.2');
    page.dispose();
  });
});
