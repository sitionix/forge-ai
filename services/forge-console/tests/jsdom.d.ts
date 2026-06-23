declare module 'jsdom' {
  export class JSDOM {
    constructor(html?: string, options?: Record<string, unknown>);
    window: Window & typeof globalThis & Record<string, unknown>;
  }
}

declare module '../src/operator/operator-bootstrap.js' {
  export const bootstrapOperatorConsole: any;
}

declare module '../src/operator/knowledge-overview-page.js' {
  export const KnowledgeOverviewPage: any;
  export const normalizeKnowledgeOverviewPayload: any;
}

declare module '../src/operator/knowledge-graph-client.js' {
  export const createKnowledgeGraphClient: any;
}

declare module '../src/operator/knowledge-graph-page.js' {
  export const KnowledgeGraphPage: any;
  export const knowledgeGraphNodeRadius: any;
}

declare module '../src/operator/jarvis-page.js' {
  export const JarvisPage: any;
}
