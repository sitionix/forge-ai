import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { JarvisPage } from '../src/operator/jarvis-page.js';

function jarvisDom() {
  return new JSDOM(`<!doctype html>
    <body data-page="jarvis">
      <form id="jarvisQueryForm">
        <textarea id="jarvisQueryText"></textarea>
        <button id="sendJarvisQuery" type="submit">Send</button>
      </form>
      <div id="jarvisQueryLoading" class="hidden"></div>
      <div id="jarvisQueryError" class="hidden"></div>
      <section id="jarvisQueryResult" class="hidden"></section>
      <section id="jarvisQueryDiagnostics" class="hidden"></section>
      <section id="jarvisQueryRaw" class="hidden"></section>
    </body>`, {
    url: 'http://127.0.0.1/operator/jarvis.html',
    pretendToBeVisual: true
  });
}

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

async function flushAsync() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

function submitEvent() {
  return { preventDefault: () => undefined };
}

function queryPayload(queryText: string) {
  return {
    queryText,
    intent: 'FLOW_EXPLANATION',
    answerLanguage: 'uk',
    includeTests: false,
    maxFlows: 3
  };
}

function baseResponse(overrides: Record<string, unknown> = {}) {
  return {
    queryId: 'q',
    status: 'OK',
    intent: 'FLOW_EXPLANATION',
    matchedSources: [{ sourceId: 'svc', displayName: 'Service', score: 0.9 }],
    matchedNodes: [{ sourceId: 'svc', nodeKind: 'CALLABLE', label: 'entrypoint', score: 0.9, matchReasons: ['NAME_MATCH'] }],
    flows: [],
    flowExplanations: [],
    coverage: { searchedSourceCount: 1, matchedSourceCount: 1, matchedNodeCount: 1, flowCount: 0, nodeCount: 0, edgeCount: 0, evidenceCount: 0 },
    diagnostics: [],
    ...overrides
  };
}

function branchingFlow() {
  return {
    flowIndex: 1,
    source: 'site-service',
    entrypoint: { nodeRef: 'n1', label: 'entrypoint', kind: 'CALLABLE', relativePath: 'src/SiteController.java' },
    entrypointOrigin: 'EXPLICIT_GRAPH_FACT',
    matchedAnchors: [{ anchorRef: 'n1', label: 'entrypoint', score: 0.98, distance: 0, matchReasons: ['NAME_MATCH'] }],
    nodes: [
      { nodeRef: 'n1', label: 'entrypoint', kind: 'CALLABLE', relativePath: 'src/SiteController.java' },
      { nodeRef: 'n2', label: 'worker A', kind: 'CALLABLE' },
      { nodeRef: 'n3', label: 'worker B', kind: 'CALLABLE' },
      { nodeRef: 'n4', label: 'worker C', kind: 'CALLABLE' }
    ],
    transitions: [
      { transitionRef: 't1', fromNodeRef: 'n1', toNodeRef: 'n2', evidenceRefs: ['e-t1'] },
      { transitionRef: 't2', fromNodeRef: 'n1', toNodeRef: 'n3', evidenceRefs: ['e-t2'] },
      { transitionRef: 't3', fromNodeRef: 'n1', toNodeRef: 'n4', evidenceRefs: ['e-t3'] }
    ],
    boundaries: [],
    evidence: [
      { evidenceRef: 'e-n1', ownerRef: 'n1', relativePath: 'src/SiteController.java', lineStart: 10, lineEnd: 12, excerpt: 'entry evidence' },
      { evidenceRef: 'e-t1', ownerRef: 't1', relativePath: 'src/A.java', lineStart: 1, lineEnd: 1, excerpt: 'edge A' },
      { evidenceRef: 'e-t2', ownerRef: 't2', relativePath: 'src/B.java', lineStart: 2, lineEnd: 2, excerpt: 'edge B' },
      { evidenceRef: 'e-t3', ownerRef: 't3', relativePath: 'src/C.java', lineStart: 3, lineEnd: 3, excerpt: 'edge C' }
    ],
    complete: true,
    coverage: { nodeCount: 4, transitionCount: 3, boundaryCount: 0, anchorCount: 1, maxDepthReached: 1, cycleDetected: false, truncated: false },
    diagnostics: []
  };
}

function branchingExplanation(status = 'OK') {
  return {
    flowIndex: 1,
    title: 'Site creation flow',
    narrative: status === 'OK' ? [{ text: 'The entrypoint calls three workers as sibling branches.', nodeRefs: ['n1'], transitionRefs: ['t1', 't2', 't3'], boundaryRefs: [] }] : [],
    steps: [
      { nodeRef: 'n1', nodeLabel: 'entrypoint', explanation: status === 'OK' ? 'Receives the request.' : undefined, transitionRefs: ['t1', 't2', 't3'], evidenceRefs: ['e-n1'] },
      { nodeRef: 'n2', nodeLabel: 'worker A', explanation: status === 'OK' ? 'Handles branch A.' : undefined, transitionRefs: [], evidenceRefs: [] },
      { nodeRef: 'n3', nodeLabel: 'worker B', explanation: status === 'OK' ? 'Handles branch B.' : undefined, transitionRefs: [], evidenceRefs: [] },
      { nodeRef: 'n4', nodeLabel: 'worker C', explanation: status === 'OK' ? 'Handles branch C.' : undefined, transitionRefs: [], evidenceRefs: [] }
    ],
    transitionExplanations: status === 'OK' ? [
      { transitionRef: 't1', explanation: 'Calls worker A.', evidenceRefs: ['e-t1'] },
      { transitionRef: 't2', explanation: 'Calls worker B.', evidenceRefs: ['e-t2'] },
      { transitionRef: 't3', explanation: 'Calls worker C.', evidenceRefs: ['e-t3'] }
    ] : [],
    boundaries: [],
    status
  };
}

async function renderResponse(response: Record<string, unknown>, runtimeConfig: Record<string, unknown> = {}) {
  const dom = jarvisDom();
  const http = {
    post: vi.fn(() => Promise.resolve(response))
  };
  const page = new JarvisPage({ document: dom.window.document, http, runtimeConfig });
  (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'як створюється сайт';
  await page.submitQuery(submitEvent());
  return { dom, page, http };
}

describe('Jarvis flow explanation cards', () => {
  it('submits exactly one flow explanation request and aborts disposal', async () => {
    const dom = jarvisDom();
    const pending = deferred<Record<string, unknown>>();
    let signal: AbortSignal | undefined;
    const http = {
      post: vi.fn((_path: string, _body: unknown, options: { signal?: AbortSignal }) => {
        signal = options.signal;
        return pending.promise;
      })
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'як створюється сайт';

    const first = page.submitQuery(submitEvent());
    const second = page.submitQuery(submitEvent());
    await flushAsync();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', queryPayload('як створюється сайт'), expect.any(Object));
    expect((http.post.mock.calls as unknown as Array<[string, unknown, unknown]>).some(([path]) => path.includes('/knowledge'))).toBe(false);
    expect(signal?.aborted).toBe(false);
    await second;

    page.dispose();
    expect(signal?.aborted).toBe(true);
    pending.resolve(baseResponse());
    await first;
  });

  it('renders branching siblings without a synthetic worker sequence', async () => {
    const { dom } = await renderResponse(baseResponse({
      flows: [branchingFlow()],
      flowExplanations: [branchingExplanation()],
      coverage: { flowCount: 1, nodeCount: 4, edgeCount: 3, evidenceCount: 4 }
    }));

    const document = dom.window.document;
    expect(document.querySelectorAll('.jarvis-flow-card')).toHaveLength(1);
    expect(document.querySelectorAll('.jarvis-graph-transition')).toHaveLength(3);
    const transitionTexts = [...document.querySelectorAll('.jarvis-graph-transition')].map((item) => item.textContent || '');
    expect(transitionTexts).toEqual(expect.arrayContaining([
      expect.stringContaining('worker A'),
      expect.stringContaining('worker B'),
      expect.stringContaining('worker C')
    ]));
    expect(transitionTexts.find((item) => item.includes('worker A'))).not.toContain('worker B');
    expect(document.querySelector('.jarvis-graph-node.matched')?.textContent).toContain('entrypoint');
    expect(document.body.textContent).toContain('The entrypoint calls three workers as sibling branches.');
    expect(transitionTexts.find((item) => item.includes('worker A'))).toContain('edge A');
    expect(transitionTexts.find((item) => item.includes('worker A'))).not.toContain('edge B');
  });

  it('keeps several entrypoint flows distinct', async () => {
    const flowOne = branchingFlow();
    const flowTwo = {
      ...branchingFlow(),
      flowIndex: 2,
      source: 'billing-service',
      entrypoint: { nodeRef: 'm1', label: 'billing entrypoint', kind: 'CALLABLE' },
      entrypointOrigin: 'INFERRED_ROOT',
      matchedAnchors: [],
      nodes: [{ nodeRef: 'm1', label: 'billing entrypoint', kind: 'CALLABLE' }],
      transitions: [],
      evidence: []
    };
    const { dom } = await renderResponse(baseResponse({
      flows: [flowTwo, flowOne],
      flowExplanations: [
        branchingExplanation(),
        { flowIndex: 2, title: 'Billing flow', narrative: [{ text: 'Billing starts from an inferred root.', nodeRefs: ['m1'], transitionRefs: [], boundaryRefs: [] }], steps: [{ nodeRef: 'm1', nodeLabel: 'billing entrypoint' }], transitionExplanations: [], boundaries: [], status: 'OK' }
      ],
      coverage: { flowCount: 2, nodeCount: 5, edgeCount: 3, evidenceCount: 4 }
    }));

    const cards = [...dom.window.document.querySelectorAll('.jarvis-flow-card')];
    expect(cards).toHaveLength(2);
    const firstCard = cards[0] as Element;
    const secondCard = cards[1] as Element;
    expect(firstCard.textContent).toContain('site-service');
    expect(secondCard.textContent).toContain('billing-service');
    expect(firstCard.querySelector('[data-jarvis-flow-toggle]')?.getAttribute('aria-expanded')).toBe('true');
    expect(secondCard.querySelector('[data-jarvis-flow-toggle]')?.getAttribute('aria-expanded')).toBe('false');
    expect(dom.window.document.body.textContent).toContain('2 distinct entrypoint flows returned');
    expect(dom.window.document.body.textContent).toContain('No explicit persisted entrypoint fact was reachable');
  });

  it('renders cycles and shared downstream nodes without infinite traversal', async () => {
    const flow = {
      flowIndex: 1,
      source: 'svc',
      entrypoint: { nodeRef: 'a', label: 'A', kind: 'CALLABLE' },
      entrypointOrigin: 'EXPLICIT_GRAPH_FACT',
      matchedAnchors: [],
      nodes: [
        { nodeRef: 'a', label: 'A', kind: 'CALLABLE' },
        { nodeRef: 'b', label: 'B', kind: 'CALLABLE' },
        { nodeRef: 'c', label: 'C', kind: 'CALLABLE' },
        { nodeRef: 'd', label: 'D', kind: 'CALLABLE' },
        { nodeRef: 's', label: 'Shared', kind: 'CALLABLE' }
      ],
      transitions: [
        { transitionRef: 'ab', fromNodeRef: 'a', toNodeRef: 'b', evidenceRefs: [] },
        { transitionRef: 'ba', fromNodeRef: 'b', toNodeRef: 'a', evidenceRefs: [] },
        { transitionRef: 'ac', fromNodeRef: 'a', toNodeRef: 'c', evidenceRefs: [] },
        { transitionRef: 'cs', fromNodeRef: 'c', toNodeRef: 's', evidenceRefs: [] },
        { transitionRef: 'ad', fromNodeRef: 'a', toNodeRef: 'd', evidenceRefs: [] },
        { transitionRef: 'ds', fromNodeRef: 'd', toNodeRef: 's', evidenceRefs: [] }
      ],
      boundaries: [],
      evidence: [],
      complete: true,
      coverage: {},
      diagnostics: []
    };
    const { dom } = await renderResponse(baseResponse({
      flows: [flow],
      flowExplanations: [{ flowIndex: 1, title: 'Cycle flow', narrative: [{ text: 'The graph contains a cycle and a shared node.', nodeRefs: ['a'], transitionRefs: ['ab'], boundaryRefs: [] }], steps: flow.nodes.map((node) => ({ nodeRef: node.nodeRef, nodeLabel: node.label })), transitionExplanations: [], boundaries: [], status: 'OK' }]
    }));

    const textValue = dom.window.document.body.textContent || '';
    expect(textValue).toContain('Cycle reference');
    expect(textValue).toContain('Shared downstream node');
    expect(dom.window.document.querySelectorAll('.jarvis-graph-row').length).toBeLessThan(20);
    expect(textValue).toContain('calls');
  });

  it('keeps factual graph, boundaries, and evidence when explanation failed', async () => {
    const flow = {
      ...branchingFlow(),
      boundaries: [
        { boundaryRef: 'b1', fromNodeRef: 'n1', kind: 'EXTERNAL', resolutionStatus: 'EXTERNAL_TARGET', target: 'PaymentApi', evidenceRefs: ['e-b1'] },
        { boundaryRef: 'b2', fromNodeRef: 'n1', kind: 'UNRESOLVED', resolutionStatus: 'DYNAMIC_TARGET', target: 'dynamicMapper', evidenceRefs: ['e-b2'] },
        { boundaryRef: 'b3', fromNodeRef: 'n1', kind: 'CURRENT_TARGET_NODE_MISSING', resolutionStatus: 'CURRENT_TARGET_NODE_MISSING', target: null, evidenceRefs: ['e-b3'] }
      ],
      evidence: [
        ...branchingFlow().evidence,
        { evidenceRef: 'e-b1', ownerRef: 'b1', relativePath: 'src/External.java', lineStart: 4, lineEnd: 5, excerpt: 'external evidence' },
        { evidenceRef: 'e-b2', ownerRef: 'b2', relativePath: 'src/Dynamic.java', lineStart: 6, lineEnd: 7, excerpt: 'dynamic evidence' },
        { evidenceRef: 'e-b3', ownerRef: 'b3', relativePath: 'src/Missing.java', lineStart: 8, lineEnd: 9, excerpt: 'missing evidence' }
      ],
      coverage: { nodeCount: 4, transitionCount: 3, boundaryCount: 3, anchorCount: 1 }
    };
    const { dom } = await renderResponse(baseResponse({
      flows: [flow],
      flowExplanations: [{ ...branchingExplanation('FAILED'), boundaries: flow.boundaries, status: 'FAILED' }]
    }));

    const bodyText = dom.window.document.body.textContent || '';
    expect(bodyText).toContain('The factual flow was found, but the local model could not produce a valid explanation.');
    expect(bodyText).toContain('worker A');
    expect(bodyText).not.toContain('The entrypoint calls three workers as sibling branches.');
    expect(bodyText).toContain('External call');
    expect(bodyText).toContain('Unresolved or dynamic call');
    expect(bodyText).toContain('Target missing from current graph');
    expect(bodyText).toContain('external evidence');
    expect(bodyText).toContain('dynamic evidence');
    expect(bodyText).toContain('missing evidence');
  });

  it('progressively renders large already-loaded flows', async () => {
    const nodes = Array.from({ length: 12 }, (_, index) => ({ nodeRef: `n${index + 1}`, label: `Node ${index + 1}`, kind: 'CALLABLE' }));
    const transitions = nodes.slice(0, -1).map((node, index) => ({
      transitionRef: `t${index + 1}`,
      fromNodeRef: node.nodeRef,
      toNodeRef: nodes[index + 1]?.nodeRef || '',
      evidenceRefs: []
    }));
    const largeFlow = {
      flowIndex: 2,
      source: 'large-service',
      entrypoint: nodes[0],
      entrypointOrigin: 'EXPLICIT_GRAPH_FACT',
      matchedAnchors: [],
      nodes,
      transitions,
      boundaries: [],
      evidence: [],
      complete: true,
      coverage: { nodeCount: 12, transitionCount: 11, boundaryCount: 0 },
      diagnostics: []
    };
    const { dom } = await renderResponse(baseResponse({
      flows: [branchingFlow(), largeFlow],
      flowExplanations: [
        branchingExplanation(),
        { flowIndex: 2, title: 'Large flow', narrative: [{ text: 'Large flow narrative.', nodeRefs: ['n1'], transitionRefs: [], boundaryRefs: [] }], steps: nodes.map((node) => ({ nodeRef: node.nodeRef, nodeLabel: node.label })), transitionExplanations: [], boundaries: [], status: 'OK' }
      ]
    }), { jarvisFlowRenderBatchSize: 5 });

    const cards = [...dom.window.document.querySelectorAll('.jarvis-flow-card')];
    const largeCard = cards[1] as Element;
    expect(largeCard.querySelectorAll('.jarvis-graph-node')).toHaveLength(0);
    (largeCard.querySelector('[data-jarvis-flow-toggle]') as HTMLButtonElement).click();
    expect(largeCard.querySelectorAll('.jarvis-graph-node')).toHaveLength(5);
    expect(largeCard.textContent).toContain('Rendered 5 of 12 nodes');
    (largeCard.querySelector('[data-jarvis-show-more]') as HTMLButtonElement).click();
    expect(largeCard.querySelectorAll('.jarvis-graph-node')).toHaveLength(10);
    expect(largeCard.textContent).toContain('Rendered 10 of 12 nodes');
    expect(largeCard.textContent).toContain('Nodes: 12');
  });

  it('escapes backend and query strings as text', async () => {
    const dom = jarvisDom();
    const malicious = '<img src=x onerror="window.__jarvisXss=1"><script>window.__jarvisXss=1</script>';
    const flow = {
      ...branchingFlow(),
      entrypoint: { nodeRef: 'n1', label: malicious, kind: 'CALLABLE' },
      nodes: [{ nodeRef: 'n1', label: malicious, kind: 'CALLABLE' }],
      transitions: [],
      boundaries: [{ boundaryRef: 'b1', fromNodeRef: 'n1', kind: 'EXTERNAL', resolutionStatus: 'EXTERNAL_TARGET', target: malicious, evidenceRefs: ['e1'] }],
      evidence: [{ evidenceRef: 'e1', ownerRef: 'b1', relativePath: malicious, lineStart: 1, lineEnd: 1, excerpt: malicious }]
    };
    const boundary = flow.boundaries[0];
    const http = {
      post: vi.fn(() => Promise.resolve(baseResponse({
        matchedNodes: [{ label: malicious, sourceId: 'svc', nodeKind: 'CALLABLE', score: 1 }],
        flows: [flow],
        flowExplanations: [{ flowIndex: 1, title: malicious, narrative: [{ text: malicious, nodeRefs: ['n1'], transitionRefs: [], boundaryRefs: ['b1'] }], steps: [{ nodeRef: 'n1', nodeLabel: malicious, explanation: malicious }], transitionExplanations: [], boundaries: [{ ...boundary, explanation: malicious }], status: 'OK' }],
        diagnostics: [{ code: malicious, message: malicious }]
      })))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = malicious;

    await page.submitQuery(submitEvent());

    expect(dom.window.document.querySelector('script')).toBeNull();
    expect(dom.window.document.querySelector('img')).toBeNull();
    expect(dom.window.__jarvisXss).toBeUndefined();
    expect(dom.window.document.body.textContent).toContain('<script>window.__jarvisXss=1</script>');
  });
});
