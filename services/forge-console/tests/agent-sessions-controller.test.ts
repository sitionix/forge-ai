import { describe,it,expect,vi,afterEach } from 'vitest';
import { AgentSessionsController } from '../src/operator/agent-sessions-controller.js';
afterEach(()=>vi.useRealTimers());
const session={id:'s',projectId:'p',revision:1,status:'READY',agentName:'Reviewer',activeTurnId:null};
const api=()=>({getAgentSession:vi.fn().mockResolvedValue({session,messages:{items:[],nextSequence:0,hasMore:false}}),listAgentSessionEvents:vi.fn().mockResolvedValue({session,items:[],nextSequence:0,hasMore:false}),listAgentSessionMessages:vi.fn().mockResolvedValue({items:[],nextSequence:0,hasMore:false}),sendAgentSessionMessage:vi.fn(),cancelAgentSession:vi.fn()});
describe('standalone session requests',()=>{
 it('does not roll back a terminal poll when an older receipt arrives',async()=>{
  vi.useFakeTimers();const a=api();let accept:any;a.sendAgentSessionMessage.mockImplementation(()=>new Promise(r=>accept=r));
  const c=new AgentSessionsController({api:a,onChange:vi.fn()});await c.open('p','s');c.setDraft('task');const sending=c.send();
  a.listAgentSessionEvents.mockResolvedValue({session:{...session,revision:3},items:[],nextSequence:0,hasMore:false});await c.poll();
  accept({sessionId:'s',turnId:'completed',revision:2,accepted:true});await sending;
  expect(c.state.session.revision).toBe(3);expect(c.state.session.activeTurnId).toBeNull();c.dispose();
 });
 it('refreshes current state after a duplicate receipt instead of treating its turn as active',async()=>{
  vi.useFakeTimers();const a=api();a.sendAgentSessionMessage.mockResolvedValue({sessionId:'s',turnId:'completed',revision:2,accepted:false});
  a.listAgentSessionEvents.mockResolvedValue({session:{...session,revision:3},items:[],nextSequence:0,hasMore:false});
  const c=new AgentSessionsController({api:a,onChange:vi.fn()});await c.open('p','s');c.setDraft('task');await c.send();
  expect(c.state.session.activeTurnId).toBeNull();expect(c.state.session.revision).toBe(3);c.dispose();
 });
 it('keeps unsent draft and ignores old route responses',async()=>{
  vi.useFakeTimers();const a=api();let resolve:any;a.getAgentSession.mockImplementationOnce(()=>new Promise(r=>resolve=r));
  const c=new AgentSessionsController({api:a,onChange:vi.fn()});const old=c.open('p','old');await c.open('p','s');c.setDraft('unsent');resolve({session:{...session,id:'old'},messages:{items:[],nextSequence:0}});await old;
  expect(c.state.session.id).toBe('s');expect(c.state.draft).toBe('unsent');c.dispose();
 });
 it('deduplicates events by sequence and keeps the server turn identity',async()=>{
  vi.useFakeTimers();const a=api();a.listAgentSessionEvents.mockResolvedValue({session,items:[{sequence:1,turnId:'turn',type:'TOOL_CALL'}] as any,nextSequence:1,hasMore:false});
  const c=new AgentSessionsController({api:a,onChange:vi.fn()});await c.open('p','s');await c.poll();await c.poll();
  expect(c.state.events).toHaveLength(1);expect(c.state.events[0].turnId).toBe('turn');c.dispose();
 });
 it('retries a lost receipt with the same requestId and preserves newer draft',async()=>{
  vi.useFakeTimers();const a=api();a.sendAgentSessionMessage.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({sessionId:'s',turnId:'t',revision:2,accepted:false});
  const c=new AgentSessionsController({api:a,onChange:vi.fn()});await c.open('p','s');c.setDraft('first');await c.send();c.setDraft('newer text');await c.send();
  expect(a.sendAgentSessionMessage.mock.calls[0]?.[2]).toEqual(a.sendAgentSessionMessage.mock.calls[1]?.[2]);expect(c.state.draft).toBe('newer text');c.dispose();
 });
});
