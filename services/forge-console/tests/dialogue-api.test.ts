import { describe, expect, it, vi } from 'vitest';
import { createAgentProjectsApi } from '../src/operator/agent-projects-api.js';
describe('Dialogue API',()=>{it('uses Nexus routes and forwards exact command IDs/revisions and cursors',()=>{
 const http={get:vi.fn(),post:vi.fn()};const api=createAgentProjectsApi(http);const base='/agents/workflow-runs/run%2Fid/node-runs/node%2Fid/dialogue';
 api.getDialogue('run/id','node/id');expect(http.get).toHaveBeenLastCalledWith(base);
 api.listDialogueMessages('run/id','node/id',42,20);expect(http.get).toHaveBeenLastCalledWith(base+'/messages?afterSequence=42&limit=20');
 for(const [method,path] of [['sendDialogueMessage','messages'],['summarizeDialogue','summary'],['completeDialogue','complete']]){
  const request={requestId:'uuid',expectedRevision:7,text:'😀\nText',summaryRevisionId:'summary',outputPortId:'port'};
  api[method!]('run/id','node/id',request);expect(http.post).toHaveBeenLastCalledWith(base+'/'+path,request);
 }
});});
