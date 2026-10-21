import {describe,it,expect,vi} from 'vitest';
import {JSDOM} from 'jsdom';
import {AiLibraryView,librarySummary} from '../src/operator/ai-library-view.js';
const empty={status:'EMPTY',items:[],diagnostics:[]};
const unsupported={status:'UNSUPPORTED',items:[],diagnostics:[] as string[]};
function state(){return {projectId:'p',loading:false,error:'',snapshot:{projectId:'p',provider:{providerDisplayName:'Codex',modelDisplayName:'Model'},categories:{skills:{status:'AVAILABLE',items:Array.from({length:10000},(_,i)=>({id:`id${i}`,name:i===0?'<img src=x onerror=alert(1)>':`skill ${i}`,description:'description',source:'repo'})),diagnostics:[]},agents:unsupported,flows:unsupported,tools:{status:'UNAVAILABLE',items:[],diagnostics:['MCP_EXECUTION_CONTEXT_REQUIRED']}}}};}
describe('AI Library rendering',()=>{
 it('shows one combined native agent catalog without a scope selector',()=>{
  const dom=new JSDOM('<section id="aiLibrary"></section>');
  const view=new AiLibraryView({document:dom.window.document,onRefresh:vi.fn(),onNavigate:vi.fn()});
  const inventory=state();inventory.snapshot.categories.agents={status:'EMPTY',items:[],diagnostics:['AGENT_FILES_ONLY']};
  view.render(inventory,{category:'agents'});
  expect(librarySummary(inventory)).toContain('0 Agents');
  expect(dom.window.document.body.textContent).toContain('Global and project definitions form one catalog');
  expect(dom.window.document.querySelectorAll('select')).toHaveLength(0);
  expect(dom.window.document.body.textContent).not.toContain('0 project agents');
 });
 it('shows project agent definitions as a scoped supported catalog',()=>{
  const dom=new JSDOM('<section id="aiLibrary"></section>');
  const view=new AiLibraryView({document:dom.window.document,onRefresh:vi.fn(),onNavigate:vi.fn()});
  const inventory=state();inventory.snapshot.categories.agents={status:'EMPTY',items:[],diagnostics:['PROJECT_AGENT_FILES_ONLY']};
  view.render(inventory,{category:'agents'});
  expect(librarySummary(inventory)).toContain('0 project agents');
  expect(dom.window.document.body.textContent).toContain('Project agent definitions');
  expect(dom.window.document.body.textContent).not.toContain('does not expose');
 });
 it('bounds large inventories and renders provider text safely',()=>{const dom=new JSDOM('<section id="aiLibrary"></section>');const view=new AiLibraryView({document:dom.window.document,onRefresh:vi.fn(),onNavigate:vi.fn()});view.render(state(),{category:'all',search:'',page:0});expect(dom.window.document.querySelectorAll('[data-library-item]').length).toBe(100);expect(dom.window.document.querySelector('img')).toBeNull();expect(dom.window.document.body.textContent).toContain('10000');expect(dom.window.document.body.textContent).toContain('Codex');});
 it('distinguishes unsupported and unavailable without fake zero counts',()=>{expect(librarySummary(state())).toContain('10000 Skills');expect(librarySummary(state())).toContain('Tools: Unavailable');expect(librarySummary(state())).not.toContain('0 Agents');});
 it('shows unsupported category and missing item details explicitly',()=>{const dom=new JSDOM('<section id="aiLibrary"></section>');const view=new AiLibraryView({document:dom.window.document,onRefresh:vi.fn(),onNavigate:vi.fn()});view.render(state(),{category:'agents'});expect(dom.window.document.body.textContent).toContain('does not expose');view.render(state(),{category:'tools'});expect(dom.window.document.body.textContent).toContain('approved execution context');expect(dom.window.document.querySelector('[data-library-status]')?.textContent).not.toContain('0 results');view.render(state(),{category:'skills',itemId:'missing'});expect(dom.window.document.body.textContent).toContain('no longer available');});
 it('keeps descriptions readable and omits pagination for a single page',()=>{
  const dom=new JSDOM('<section id="aiLibrary"></section>');const navigate=vi.fn();
  const view=new AiLibraryView({document:dom.window.document,onRefresh:vi.fn(),onNavigate:navigate});
  const inventory=state();inventory.snapshot.categories.skills.items=[{id:'native-id',name:'native-skill',description:'A complete, useful description that explains this capability.',source:'Provider'}];
  view.render(inventory,{category:'skills'});
  const row=dom.window.document.querySelector('[data-library-item]')!;
  expect(row.querySelector('h3 a')?.textContent).toBe('native-skill');
  expect(row.textContent).toContain('A complete, useful description');
  expect(dom.window.document.querySelector('[data-library-pages]')?.textContent).toBe('');
  expect(dom.window.document.querySelector('[data-library-status]')?.textContent).toBe('1 skill');
  (row.querySelector('h3 a') as HTMLAnchorElement).click();
  expect(navigate).toHaveBeenCalledWith(expect.objectContaining({category:'skills',itemId:'native-id'}));
 });
 it('can clear a search with no results without losing the selected category',()=>{
  const dom=new JSDOM('<section id="aiLibrary"></section>');const navigate=vi.fn();
  const view=new AiLibraryView({document:dom.window.document,onRefresh:vi.fn(),onNavigate:navigate});
  view.render(state(),{category:'skills',search:'unfindable'});
  expect(dom.window.document.body.textContent).toContain('No matching capabilities');
  (dom.window.document.querySelector('[data-library-clear-search]') as HTMLButtonElement).click();
  expect(navigate).toHaveBeenCalledWith({search:'',page:0,itemId:null});
 });
 it('shows a short readable preview while retaining the full description in details',()=>{
  const dom=new JSDOM('<section id="aiLibrary"></section>');
  const view=new AiLibraryView({document:dom.window.document,onRefresh:vi.fn(),onNavigate:vi.fn()});
  const inventory=state();const description='A useful capability for the project. '+ 'Additional provider guidance. '.repeat(20);
  inventory.snapshot.categories.skills.items=[{id:'native',name:'skill',description,source:'Provider'}];
  view.render(inventory,{category:'skills'});
  expect(dom.window.document.querySelector('[data-library-item] p')?.textContent).toBe('A useful capability for the project.');
  view.render(inventory,{category:'skills',itemId:'native'});
  expect(dom.window.document.querySelector('.ai-library-description')?.textContent).toBe(description);
 });
 it('announces an asynchronous inventory failure through the live status',()=>{
  const dom=new JSDOM('<section id="aiLibrary"></section>');
  const view=new AiLibraryView({document:dom.window.document,onRefresh:vi.fn(),onNavigate:vi.fn()});
  view.render({...state(),loading:true,snapshot:null});
  view.render({...state(),snapshot:null,error:'Inventory unavailable. Refresh to try again.'});
  expect(dom.window.document.querySelector('[role="status"]')?.textContent).toContain('Inventory unavailable');
 });
 it('labels native MCP coverage without claiming the built-in tool catalog is empty',()=>{
  const dom=new JSDOM('<section id="aiLibrary"></section>');
  const view=new AiLibraryView({document:dom.window.document,onRefresh:vi.fn(),onNavigate:vi.fn()});
  const inventory=state();inventory.snapshot.categories.tools={status:'EMPTY',items:[],diagnostics:['MCP_TOOLS_ONLY']};
  view.render(inventory,{category:'tools'});
  expect(dom.window.document.body.textContent).toContain('Built-in');
  expect(dom.window.document.body.textContent).toContain('MCP');
  expect(librarySummary(inventory)).toContain('0 MCP tools');
  view.render(inventory,{category:'agents'});
  expect(dom.window.document.body.textContent).toContain('does not expose');
  expect(dom.window.document.body.textContent).not.toContain('Agents are not supported');
 });
});
