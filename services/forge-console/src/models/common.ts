export interface ApiDiagnostic {
  code: string;
  message: string;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  limit: number;
  offset: number;
}
