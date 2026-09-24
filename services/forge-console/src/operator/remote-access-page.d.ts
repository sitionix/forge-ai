export class RemoteAccessPage {
  constructor(options: {document: Document; window: Window | unknown; fetcher?: (url: string, init: RequestInit) => Promise<Response>; runtimeConfig?: Record<string, unknown>});
  mount(): Promise<void>;
  refresh(): Promise<void>;
  dispose(): void;
}
