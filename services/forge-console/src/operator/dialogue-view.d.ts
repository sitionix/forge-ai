export interface DialogueViewOptions {
  runId: string;
  nodeRunId: string;
  ports: Array<{ sourcePortId: string; name: string; description?: string; dialogueDisposition: 'ACCEPT' | 'REWORK' | 'DEFER' }>;
  readOnly?: boolean;
  onChange?: (state: any) => void;
}
export class DialogueView {
  constructor(options: { document: Document; window?: any; api: any; pollIntervalMs?: number });
  open(host: HTMLElement, options: DialogueViewOptions): Promise<void>;
  close(): void;
  refresh(): Promise<void>;
  send(): Promise<void>;
  summarize(): Promise<void>;
  complete(outputPortId: string): Promise<void>;
  confirmCompletion(): Promise<void>;
  retry(): Promise<void>;
}
