import {LlmApi,LlmProvider} from './llm-api.js';
export class LlmProvidersView {
 constructor(options:{document:Document;window:Window;api:LlmApi;onChanged?:(provider:LlmProvider)=>void});
 start():Promise<void>;
 dispose():void;
}
