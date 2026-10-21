import {describe,it,expect,vi} from 'vitest';
import {AiLibraryController} from '../src/operator/ai-library-controller.js';
function snapshot(projectId:string,revision=1){return {projectId,profileRevision:revision,provider:{providerId:'codex',modelId:'m'},categories:{}};}
describe('AI Library request ownership',()=>{
 it('ignores a response after project navigation even when abort is ignored',async()=>{
  let resolve:any;const api={getAiLibrary:vi.fn((id:string)=>id==='a'?new Promise(r=>{resolve=r;}):Promise.resolve(snapshot(id)))};
  const c=new AiLibraryController({api,onChange:vi.fn()});const a=c.load('a');await Promise.resolve();await c.load('b');resolve(snapshot('a'));await a;expect(c.state.snapshot.projectId).toBe('b');c.dispose();
 });
 it('invalidates an old revision when the same provider is selected again',async()=>{
  let resolve:any;const api={getAiLibrary:vi.fn(()=>new Promise(r=>{resolve=r;}))};const c=new AiLibraryController({api,onChange:vi.fn()});
  c.observeProfileRevision(1);const request=c.load('a');await Promise.resolve();c.observeProfileRevision(2);c.observeProfileRevision(3);resolve(snapshot('a',1));await request;expect(c.state.snapshot).toBeNull();expect(c.state.loading).toBe(false);c.dispose();
 });
 it('reloads when the first observed revision is newer than the displayed snapshot',async()=>{
  const c=new AiLibraryController({api:{getAiLibrary:vi.fn().mockResolvedValue(snapshot('a',1))},onChange:vi.fn()});await c.load('a');expect(c.observeProfileRevision(2)).toBe(true);expect(c.state.snapshot).toBeNull();c.dispose();
 });
 it('shares an in-flight summary request and bounds cached inventory lifetime',async()=>{
  const api={getAiLibrary:vi.fn().mockResolvedValue(snapshot('a'))};const c=new AiLibraryController({api,onChange:vi.fn()});await Promise.all([c.load('a'),c.load('a')]);await c.load('a');expect(api.getAiLibrary).toHaveBeenCalledTimes(1);await c.load('a',{force:true});expect(api.getAiLibrary).toHaveBeenCalledTimes(2);c.dispose();
 });
});

it('recovers after transient profile failure without requiring manual refresh', async () => {
 const {AgentProjectsPage}=await import('../src/operator/agent-projects-page.js');
 const library=new AiLibraryController({api:{getAiLibrary:vi.fn().mockResolvedValue(snapshot('a',1))},onChange:vi.fn()});
 await library.load('a');library.observeProfileRevision(1);
 const profile=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue({revision:1,llmProfile:{providerId:'codex'}});
 const page:any={libraryVisible:()=>true,api:{getActiveProfile:profile},libraryController:library,state:{selectedProjectId:'a'}};
 await AgentProjectsPage.prototype.checkLibraryProfile.call(page);expect(library.state.snapshot).toBeNull();
 await AgentProjectsPage.prototype.checkLibraryProfile.call(page);for(let i=0;i<14;i++) await Promise.resolve();
 expect(library.state.snapshot?.projectId).toBe('a');library.dispose();
});
