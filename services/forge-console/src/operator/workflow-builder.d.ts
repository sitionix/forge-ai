export class WorkflowBuilder {
  constructor(options: { document: Document; window?: Window; api: any; onBack?: () => void; onSaved?: (workflow: any) => void });
  workflow: any;
  nodeEditorDraft: any;
  open(workflow: any, project: any, agents: any[]): void;
  openNodeEditor(nodeId: string): void;
  saveNodeEditor(): void;
  renderNodeEditor(): void;
}
