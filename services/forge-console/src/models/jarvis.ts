export interface JarvisStatus {
  status: string;
  host: string;
  port: number;
  model: {
    defaultModel: string;
  };
  ollama: {
    baseUrl: string;
    status: string;
  };
  actions: {
    count: number;
  };
}

export interface JarvisAction {
  action: string;
  description: string;
  targets: string[];
}

export interface JarvisActionsResponse {
  actions: JarvisAction[];
}
