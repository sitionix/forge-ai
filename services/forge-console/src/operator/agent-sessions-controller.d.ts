export class AgentSessionsController {
 constructor(options: any); state: any;
 open(projectId: string, sessionId?: string | null, options?: any): Promise<void>;
 setDraft(text: string): void; poll(): Promise<void>; send(): Promise<void>; cancel(): Promise<void>;
 leave(): void; dispose(): void;
}
