export function mountColdRemoteAccess(options: {
  document: Document;
  window: { location: {pathname: string; assign(url: string): void}; setTimeout: typeof setTimeout };
  fetcher?: (url: string, options: RequestInit) => Promise<Response>;
  delay?: (ms: number) => Promise<void>;
}): {start(intent: 'give' | 'connect'): Promise<void>};
