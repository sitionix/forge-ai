import { afterEach, describe, expect, it, vi } from 'vitest';
import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';
import { TaskExecutionView } from '../src/operator/task-execution-view.js';
import { DialogueView } from '../src/operator/dialogue-view.js';
const cleanup: Array<() => void> = [];
afterEach(() => cleanup.splice(0).forEach(f => f()));
const ports = (['ACCEPT', 'REWORK', 'DEFER'] as const).map((dialogueDisposition, i) => ({ sourcePortId: `p${i}`, name: dialogueDisposition, dialogueDisposition }));
function state(overrides: any = {}): any { return { nodeRunId: 'node', state: 'AWAITING_REPLY', revision: 2, summaryRevisionId: null, activeTurn: null,
  latestRevision: null, completion: null, turnCount: 1, maxTurns: 100, maxMessageCodePoints: 16000, messages: { messages: [{ id:'a',sequence:1,role:'ASSISTANT',text:'Question <img src=x onerror=alert(1)>',turnId:'t1' }], nextSequence:1,hasMore:false }, ...overrides }; }
function summary(): any { return state({state:'AWAITING_REVIEW',revision:7,summaryRevisionId:'summary',latestRevision:{id:'summary',kind:'SUMMARY',revision:7,
  result:{message:'Ready',draft:{summary:'Task'},questions:[],decisions:[],sources:[{title:'Unsafe',uri:'javascript:alert(1)'},{title:'Docs',uri:'https://example.com'}],readyForReview:true}}}); }
async function setup(initial = state(), readOnly = false) {
  const dom = new JSDOM('<main id="host"></main>',{url:'http://localhost',pretendToBeVisual:true});
  const api: any = { getDialogue:vi.fn(async()=>initial),listDialogueMessages:vi.fn(async()=>({messages:[],nextSequence:1,hasMore:false})),
    sendDialogueMessage:vi.fn(async()=>state({state:'RUNNING',revision:3,activeTurn:{id:'chat'}})),summarizeDialogue:vi.fn(async()=>state({state:'RUNNING',revision:3})),
    completeDialogue:vi.fn(async()=>state({state:'COMPLETED',revision:8})) };
  const host=dom.window.document.getElementById('host')!;
  const view = new DialogueView({document:dom.window.document,window:dom.window,api,pollIntervalMs:60000});
  cleanup.push(()=>{view.close();dom.window.close();});
  await view.open(host,{runId:'run',nodeRunId:'node',ports,readOnly});
  return {view,api,host,dom};
}
function editor(host:HTMLElement) {return host.querySelector<HTMLTextAreaElement>('[data-dialogue-text]')!;}
describe('DialogueView',()=>{
 it('escapes persisted messages and unsafe sources while rendering current summary',async()=>{
  const {host}=await setup(summary());expect(host.querySelector('img')).toBeNull();
  expect(host.textContent).toContain('<img');expect(host.querySelector('a[href^="javascript:"]')).toBeNull();
  expect(host.querySelector('a[href="https://example.com/"]')).not.toBeNull();expect(host.textContent).toContain('Task');
 });
 it('loads every cursor page and preserves ordered immutable transcript',async()=>{
  const {api,view,host}=await setup();api.getDialogue.mockResolvedValue(state({messages:{messages:[],nextSequence:0,hasMore:true}}));
  api.listDialogueMessages.mockResolvedValueOnce({messages:[{id:'a',sequence:1,role:'USER',text:'First'}],nextSequence:1,hasMore:true})
   .mockResolvedValueOnce({messages:[{id:'b',sequence:2,role:'ASSISTANT',text:'Second'}],nextSequence:2,hasMore:false});
  await view.refresh();expect(api.listDialogueMessages).toHaveBeenNthCalledWith(1,'run','node',0,100);
  expect(api.listDialogueMessages).toHaveBeenNthCalledWith(2,'run','node',1,100);expect(host.textContent).toContain('Second');
 });
 it('sends preserved multiline text with server revision and UUID, then prevents busy sends',async()=>{
  const {view,api,host}=await setup();editor(host).value='😀 First\nSecond';editor(host).dispatchEvent(new host.ownerDocument.defaultView!.Event('input'));
  await view.send();expect(api.sendDialogueMessage).toHaveBeenCalledWith('run','node',expect.objectContaining({expectedRevision:2,text:'😀 First\nSecond',requestId:expect.stringMatching(/^[0-9a-f-]{36}$/i)}));
  await view.send();expect(api.sendDialogueMessage).toHaveBeenCalledTimes(1);expect(editor(host).value).toBe('');
 });
 it('retries a lost response with the same request even after polling sees a newer revision',async()=>{
  const {view,api,host}=await setup();api.sendDialogueMessage.mockRejectedValueOnce(new TypeError('Network'));
  editor(host).value='Reply';await view.send();const first=api.sendDialogueMessage.mock.calls[0]![2];
  api.getDialogue.mockResolvedValue(state({revision:4,state:'RUNNING'}));await view.refresh();await view.retry();
  expect(api.sendDialogueMessage.mock.calls[1]![2]).toEqual(first);expect(api.sendDialogueMessage).toHaveBeenCalledTimes(2);
 });
 it('reloads a conflicting tab without losing the user draft',async()=>{
  const {view,api,host}=await setup();api.sendDialogueMessage.mockRejectedValueOnce({status:409,code:'DIALOGUE_REVISION_CONFLICT'});
  api.getDialogue.mockResolvedValue(state({revision:5}));editor(host).value='Keep my answer';await view.send();
  expect(editor(host).value).toBe('Keep my answer');expect(api.getDialogue).toHaveBeenCalledTimes(2);expect(host.textContent).toContain('changed');
 });
 it('accepts only exact current summary and snapshot port; new chat disables acceptance',async()=>{
  const {view,api,host}=await setup(summary());await view.complete('p0');await view.confirmCompletion();
  expect(api.completeDialogue).toHaveBeenCalledWith('run','node',expect.objectContaining({expectedRevision:7,summaryRevisionId:'summary',outputPortId:'p0'}));
  api.getDialogue.mockResolvedValue(state({revision:9}));await view.refresh();await view.complete('p0');expect(api.completeDialogue).toHaveBeenCalledTimes(1);
 });
 it('requires explicit confirmation of a captured summary and invalidates it on polling changes',async()=>{
  const {view,api,host}=await setup(summary());await view.complete('p0');expect(api.completeDialogue).not.toHaveBeenCalled();
  expect(host.querySelector('[data-dialogue-confirm]')).not.toBeNull();
  const newer=summary();newer.revision=10;newer.summaryRevisionId='summary-B';newer.latestRevision.id='summary-B';newer.latestRevision.revision=10;
  api.getDialogue.mockResolvedValue(newer);await view.refresh();await view.confirmCompletion();expect(api.completeDialogue).not.toHaveBeenCalled();
  expect(host.textContent).toContain('changed');await view.complete('p0');await view.confirmCompletion();
  expect(api.completeDialogue).toHaveBeenCalledWith('run','node',expect.objectContaining({expectedRevision:10,summaryRevisionId:'summary-B'}));
 });
 it.each(['ACCEPT','REWORK','DEFER'])('reloads completed %s as an immutable summary with exact outcome and revision',async disposition=>{
  const completed=summary();completed.state='COMPLETED';completed.revision=8;completed.completion={summaryRevisionId:'summary',outputPortId:`p${['ACCEPT','REWORK','DEFER'].indexOf(disposition)}`,disposition,revision:8,completedAt:'2026-10-07T12:00:00Z'};
  const {host}=await setup(completed);expect(host.textContent).toContain('Completed summary');expect(host.textContent).toContain(disposition);
  expect(host.textContent).toContain('summary');expect(host.textContent).toContain('2026-10-07');expect(host.textContent).not.toContain('Working draft');
 });
 it('shows the snapshotted business output name and description before confirming it',async()=>{
  const {view,host}=await setup(summary());await view.open(host,{runId:'run',nodeRunId:'node',ports:[{sourcePortId:'p0',name:'Publish approved proposal',description:'Sends the agreed task to the implementer',dialogueDisposition:'ACCEPT'}]});
  expect(host.querySelector('[data-dialogue-complete="p0"]')!.textContent).toContain('Publish approved proposal');expect(host.textContent).toContain('Sends the agreed task');
  await view.complete('p0');expect(host.querySelector('[data-dialogue-confirmation]')!.textContent).toContain('Publish approved proposal');
 });
 it('blocking summary disables ACCEPT but permits explicit REWORK/DEFER',async()=>{
  const initial=summary();initial.latestRevision.result.readyForReview=false;initial.latestRevision.result.questions=[{id:'q',text:'Need answer',reason:'Required',blocking:true}];
  const {view,api,host}=await setup(initial);expect(host.querySelector<HTMLButtonElement>('[data-dialogue-complete="p0"]')!.disabled).toBe(true);
  await view.complete('p0');expect(api.completeDialogue).not.toHaveBeenCalled();await view.complete('p1');await view.confirmCompletion();expect(api.completeDialogue).toHaveBeenCalledTimes(1);
 });
 it('historical/terminal invocations remain read-only',async()=>{
  const {view,api,host}=await setup(summary(),true);await view.send();await view.summarize();await view.complete('p0');
  expect(api.completeDialogue).not.toHaveBeenCalled();expect(api.summarizeDialogue).not.toHaveBeenCalled();expect(editor(host).disabled).toBe(true);
 });
 it.each([8000,20000])('uses server limit %i for hint and Unicode validation',async limit=>{
  const {view,api,host}=await setup(state({maxMessageCodePoints:limit}));
  expect(host.querySelector('[id="dialogue-help"]')!.textContent).toContain(limit.toLocaleString('en-US'));
  editor(host).value='😀'.repeat(limit+1);await view.send();expect(api.sendDialogueMessage).not.toHaveBeenCalled();
  expect(host.querySelector('[data-dialogue-error]')!.textContent).toContain(limit.toLocaleString('en-US'));
  editor(host).value='😀'.repeat(limit);await view.send();
  expect(api.sendDialogueMessage).toHaveBeenCalledWith('run','node',expect.objectContaining({text:'😀'.repeat(limit)}));
 });
 it.each([undefined,0,-1])('blocks sending when server message limit is unavailable (%s)',async limit=>{
  const {view,api,host}=await setup(state({maxMessageCodePoints:limit}));
  editor(host).value='Reply';await view.send();expect(api.sendDialogueMessage).not.toHaveBeenCalled();
  expect(host.querySelector('#dialogue-help')!.textContent).toContain('unavailable');
 });
 it('rejects whitespace and over-budget Unicode text without truncation',async()=>{
  const {view,api,host}=await setup();editor(host).value='   \n';await view.send();editor(host).value='😀'.repeat(16001);await view.send();expect(api.sendDialogueMessage).not.toHaveBeenCalled();
 });
 it('polling preserves editor focus, value and selection; Enter creates lines and Ctrl+Enter sends',async()=>{
  const {view,api,host,dom}=await setup();const input=editor(host);input.value='draft';input.dispatchEvent(new dom.window.Event('input'));input.focus();input.setSelectionRange(2,2);
  await view.refresh();expect(dom.window.document.activeElement).toBe(input);expect(input.selectionStart).toBe(2);
  input.dispatchEvent(new dom.window.KeyboardEvent('keydown',{key:'Enter',bubbles:true}));expect(api.sendDialogueMessage).not.toHaveBeenCalled();
  input.dispatchEvent(new dom.window.KeyboardEvent('keydown',{key:'Enter',ctrlKey:true,bubbles:true}));await Promise.resolve();expect(api.sendDialogueMessage).toHaveBeenCalledTimes(1);
 });
 it('retains keyboard focus on unchanged actions during polling',async()=>{
  const {view,host,dom}=await setup(summary());const button=host.querySelector<HTMLButtonElement>('[data-dialogue-complete="p0"]')!;button.focus();
  await view.refresh();expect(dom.window.document.activeElement).toBe(button);
 });
 it('ignores old invocation responses after navigation',async()=>{
  const {view,api,host}=await setup();let release:any;api.getDialogue.mockImplementationOnce(()=>new Promise(resolve=>{release=resolve;}));
  const pending=view.refresh();view.close();release(summary());await pending;expect(host.querySelector('[data-dialogue-complete="p0"]')?.getAttribute('disabled')).not.toBeNull();
 });
});

describe('Dialogue execution integration',()=>{
 it('delegates chat through Nexus, preserves typing on run refresh, and selects exact latest activity turn',async()=>{
  const dom=new JSDOM(readFileSync('src/operator/agent-projects.html','utf8'),{url:'http://localhost/',pretendToBeVisual:true});
  const run:any={id:'run',workflowName:'Dialogue',status:'RUNNING',repositoryIds:[],connectionResolutions:[],executionEdges:[],
    runtimeGraph:{nodes:[{sourceNodeId:'source',nodeType:'DIALOGUE',agentName:'Groomer',scopeMode:'GLOBAL',position:{x:20,y:20}}],
      ports:ports.map(port=>({...port,sourceNodeId:'source',direction:'OUTPUT',order:0})),connections:[]},
    nodeRuns:[{id:'node',sourceNodeId:'source',nodeType:'DIALOGUE',agentName:'Groomer',status:'WAITING_FOR_DIALOGUE',repositoryId:null,contextMode:'DIALOGUE_WITHIN_NODE_RUN'}]};
  const api:any={getProjectTask:vi.fn(async()=>({id:'task',input:'Groom task',runs:[run]})),getWorkflowRun:vi.fn(async()=>run),
    getAgentExecutionContexts:vi.fn(async()=>[1,2].map(sequence=>({nodeRunId:'node',turnId:`exact-turn-${sequence}`,sequence,contextMode:'DIALOGUE_WITHIN_NODE_RUN'}))),
    getAgentExecutionEvents:vi.fn(async(turnId:string)=>({events:[{id:`event-${turnId}`,sequence:1,type:'AGENT_MESSAGE',phase:'FINAL',payload:{message:`Activity for ${turnId}`}}],captureStatus:'COMPLETE',nextAfterSequence:1,hasMore:false})),
    getDialogue:vi.fn(async()=>summary()),listDialogueMessages:vi.fn(),
    completeDialogue:vi.fn(async()=>state({state:'COMPLETED',revision:8}))};
  const view:any=new TaskExecutionView({document:dom.window.document,window:dom.window as any,api,onBack:vi.fn(),runtimeConfig:{activeJobPollIntervalMs:60000}});
  cleanup.push(()=>{view.dispose();dom.window.close();});view.bind();await view.open('task',{name:'Project'});view.selectNodeRun('node');
  for(let i=0;i<12;i++) await Promise.resolve();
  const panel=dom.window.document.getElementById('agentsV2NodeRunDetails')!;
  expect(api.getDialogue).toHaveBeenCalledWith('run','node');expect(api.getAgentExecutionEvents.mock.calls[0]?.[0]).toBe('exact-turn-2');
  const input=editor(panel);input.value='My unsent reply';input.dispatchEvent(new dom.window.Event('input'));input.focus();
  view.renderNodeDetails();expect(editor(panel)).toBe(input);expect(input.value).toBe('My unsent reply');
  panel.querySelector<HTMLButtonElement>('[data-dialogue-complete="p0"]')!.click();
  panel.querySelector<HTMLButtonElement>('[data-dialogue-confirm]')!.click();
  for(let i=0;i<16;i++) await Promise.resolve();
  expect(api.completeDialogue).toHaveBeenCalledWith('run','node',expect.objectContaining({expectedRevision:7,summaryRevisionId:'summary',outputPortId:'p0'}));
  expect(api.getWorkflowRun.mock.calls.length).toBeGreaterThan(1);expect(panel.textContent).not.toContain('this.poll');
  const turnSelector=panel.querySelector<HTMLSelectElement>('[data-dialogue-activity-turn]')!;expect(turnSelector).not.toBeNull();
  turnSelector.value='exact-turn-1';turnSelector.dispatchEvent(new dom.window.Event('change'));
  for(let i=0;i<12;i++) await Promise.resolve();
  expect(api.getAgentExecutionEvents).toHaveBeenLastCalledWith('exact-turn-1',0,200);
  expect(panel.textContent).toContain('Activity for exact-turn-1');expect(panel.textContent).not.toContain('Activity for exact-turn-2');
  api.getAgentExecutionContexts.mockResolvedValue([1,2,3].map(sequence=>({nodeRunId:'node',turnId:`exact-turn-${sequence}`,sequence,contextMode:'DIALOGUE_WITHIN_NODE_RUN'})));
  await view.pollSelectedRun();expect(panel.querySelector<HTMLSelectElement>('[data-dialogue-activity-turn]')!.value).toBe('exact-turn-1');
  expect(view.contextForNodeRun('node').turnId).toBe('exact-turn-1');

 });
});
