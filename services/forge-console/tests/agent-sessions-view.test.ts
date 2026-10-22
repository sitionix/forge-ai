// @vitest-environment jsdom
import {it,expect,vi} from 'vitest';
import {AgentSessionsView} from '../src/operator/agent-sessions-view.js';
it('keeps composer identity, focus, selection and draft while rendering runtime progress',()=>{
 document.body.innerHTML='<section id="agentSessions"></section>';
 const view=new AgentSessionsView({document,onSend:vi.fn(),onStop:vi.fn(),onDraft:vi.fn(),onSearch:vi.fn(),onOpen:vi.fn(),onNext:vi.fn()});
 const state:any={projectId:'p',sessionId:'s',session:{id:'s',projectId:'p',agentName:'Reviewer',agentSource:'FORGE',agentId:'agent',status:'RUNNING',modelId:'m',messageLimit:32768},messages:[],events:[],draft:'unsent',error:'',loading:false};
 view.render(state);const editor=document.querySelector('textarea')!;editor.focus();editor.setSelectionRange(2,4);
 view.render({...state,events:[{sequence:1,turnId:'t',type:'TOOL_CALL',payload:'{"summary":"real tool"}'}]});
 expect(document.querySelector('textarea')).toBe(editor);expect(document.activeElement).toBe(editor);expect(editor.value).toBe('unsent');expect(editor.selectionStart).toBe(2);view.dispose();
});
it('renders assistant text as text instead of executable markup',()=>{
 document.body.innerHTML='<section id="agentSessions"></section>';const view=new AgentSessionsView({document});
 view.render({projectId:'p',sessionId:'s',session:{id:'s',agentName:'Agent',status:'READY'},messages:[{sequence:1,role:'ASSISTANT',text:'<img src=x onerror="alert(1)">'}],events:[],draft:''});
 expect(document.querySelector('img')).toBeNull();expect(document.body.textContent).toContain('<img');view.dispose();
});
