export interface LlmProvider {
 providerId:'codex';authState:'SIGNED_OUT'|'CONNECTING'|'CONNECTED'|'ERROR';
 email:string|null;plan:string|null;availability:'AVAILABLE'|'UNAVAILABLE';errorCode:string|null;
}
export interface LlmLogin {
 loginId:string;status:'PENDING'|'COMPLETED'|'FAILED'|'CANCELLED'|'EXPIRED';expiresAt:string;authUrl:string|null;errorCode:string|null;
}
export function llmMessage(code?:string|null):string;
export function validCodexAuthUrl(value:unknown):boolean;
export class LlmApi {
 constructor(options?:{fetcher?:(url:string,init:RequestInit)=>Promise<Response>;location?:{pathname:string}});
 providers(signal?:AbortSignal):Promise<LlmProvider[]>;
 startLogin(signal?:AbortSignal):Promise<LlmLogin>;
 login(id:string,signal?:AbortSignal):Promise<LlmLogin>;
 cancelLogin(id:string,signal?:AbortSignal):Promise<LlmLogin>;
 logout(signal?:AbortSignal):Promise<LlmProvider>;
}
