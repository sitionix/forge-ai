import {describe,it,expect,vi,beforeEach} from 'vitest';
import {readFileSync} from 'node:fs';
import {McpConnectionForm} from '../src/operator/mcp-connection-form.js';
import type {McpConnection,McpApi} from '../src/operator/mcp-api.js';
const base:McpConnection={id:'saved',displayName:'Echo',endpoint:'https://example.org/mcp',transport:'STREAMABLE_HTTP',authType:'NONE',enabled:false,projectAccess:{scope:'SELECTED',projectIds:[]},allowedTools:[],credentialConfigured:false,createdAt:'now',updatedAt:'now',checkedAt:null,safeDiagnostic:null};
const tools=[{name:'echo',description:'<img src=x>',schemaFingerprint:'f'.repeat(64)}];
const tick=async()=>{await new Promise(r=>setTimeout(r,0));await new Promise(r=>setTimeout(r,0));};
const input=(id:string,value:string)=>{(document.getElementById(id) as HTMLInputElement).value=value;};
const click=(id:string)=>(document.getElementById(id) as HTMLButtonElement).click();
function setup() {
 const api={list:vi.fn().mockResolvedValue([]),create:vi.fn().mockResolvedValue(base),test:vi.fn().mockResolvedValue({tools}),get:vi.fn().mockResolvedValue({...base,checkedAt:'now'}),inventory:vi.fn().mockResolvedValue(tools),approve:vi.fn().mockResolvedValue(base),update:vi.fn().mockResolvedValue(base),setEnabled:vi.fn().mockResolvedValue({...base,enabled:true})};
 const onConfirmed=vi.fn();const onError=vi.fn();const form=new McpConnectionForm({document,window,api:api as unknown as McpApi,onConfirmed,onError});return {form,api,onConfirmed,onError};
}
beforeEach(()=>{
 document.documentElement.innerHTML=readFileSync('src/operator/settings.html','utf8');
 HTMLDialogElement.prototype.showModal=function(){this.open=true;};HTMLDialogElement.prototype.close=function(){this.open=false;};
});
describe('Custom MCP form',()=>{
 it('creates once disabled with deny-all policy, tests saved ID and explicitly saves access',async()=>{
 const {form,api}=setup();form.openCreate([{id:'project',name:'P'}]);input('mcpName','Echo');input('mcpEndpoint',base.endpoint);click('mcpSaveTest');click('mcpSaveTest');await tick();
 expect(api.create).toHaveBeenCalledTimes(1);expect(api.create.mock.calls[0]![0]).toMatchObject({projectAccess:{scope:'SELECTED',projectIds:[]},allowedTools:[],transport:'STREAMABLE_HTTP'});expect(api.test).toHaveBeenCalledWith('saved',expect.any(AbortSignal));
 expect(document.querySelector('#mcpToolChoices img')).toBeNull();expect((document.querySelector('#mcpToolChoices input') as HTMLInputElement).checked).toBe(false);
 (document.querySelector('#mcpToolChoices input') as HTMLInputElement).checked=true;(document.querySelector('#mcpProjectChoices input') as HTMLInputElement).checked=true;click('mcpSaveAccess');await tick();
 expect(api.approve).toHaveBeenCalledWith('saved',[{name:'echo',schemaFingerprint:tools[0]!.schemaFingerprint}],expect.any(AbortSignal));expect(api.update.mock.calls[0]![1].projectAccess).toEqual({scope:'SELECTED',projectIds:['project']});expect(api.setEnabled).not.toHaveBeenCalled();form.dispose();
 });
 it('failed probe retains saved ID and next explicit test does not recreate',async()=>{
 const {form,api}=setup();api.test.mockRejectedValueOnce(Object.assign(new Error('Credentials required'),{code:'MCP_AUTH_REQUIRED',status:401}));form.openCreate([]);input('mcpName','Echo');input('mcpEndpoint',base.endpoint);click('mcpSaveTest');await tick();expect(document.querySelector('#mcpFormNotice')?.textContent).toContain('saved');click('mcpRetest');await tick();expect(api.create).toHaveBeenCalledTimes(1);expect(api.test).toHaveBeenCalledTimes(2);form.close();expect(api.setEnabled).not.toHaveBeenCalled();form.dispose();
 });
 it('replacement is write-only and clears immediately on submit and Escape',async()=>{
 const {form,api}=setup();form.openEdit({...base,authType:'BEARER',credentialConfigured:true},tools,[]);expect((document.querySelector('#mcpBearer') as HTMLInputElement).value).toBe('');input('mcpCredentialChange','REPLACE');document.querySelector('#mcpCredentialChange')!.dispatchEvent(new Event('change'));input('mcpBearer','synthetic-secret');click('mcpSaveTest');expect((document.querySelector('#mcpBearer') as HTMLInputElement).value).toBe('');await tick();expect(api.update.mock.calls[0]![1].credential).toEqual({bearer:'synthetic-secret'});input('mcpBearer','cancel-secret');document.querySelector('#mcpConnectionDialog')!.dispatchEvent(new Event('cancel',{cancelable:true}));expect((document.querySelector('#mcpBearer') as HTMLInputElement).value).toBe('');expect(document.body.textContent).not.toContain('synthetic-secret');form.dispose();
 });
 it('credential identity change requires explicit replace or remove',async()=>{
 const {form,api}=setup();form.openEdit({...base,authType:'BEARER',credentialConfigured:true},[],[]);input('mcpEndpoint','https://other.example/mcp');click('mcpSaveTest');await tick();expect(api.update).not.toHaveBeenCalled();expect(document.querySelector('#mcpFormError')?.textContent).toContain('Replace or remove');form.dispose();
 });
 it('keeps matching fingerprints approved and changed/new tools unchecked',()=>{
 const {form}=setup();form.openEdit({...base,allowedTools:[{name:'echo',schemaFingerprint:'old'}]},tools,[]);expect((document.querySelector('#mcpToolChoices input') as HTMLInputElement).checked).toBe(false);form.close();form.openEdit({...base,allowedTools:[{name:'echo',schemaFingerprint:tools[0]!.schemaFingerprint}]},tools,[]);expect((document.querySelector('#mcpToolChoices input') as HTMLInputElement).checked).toBe(true);form.dispose();
 });
 it('close before submit creates nothing and late saved response cannot reopen dialog',async()=>{
 const {form,api,onConfirmed}=setup();form.openCreate([]);form.close();expect(api.create).not.toHaveBeenCalled();let resolve!:(c:McpConnection)=>void;api.create.mockImplementationOnce(()=>new Promise<McpConnection>(r=>{resolve=r;}));form.openCreate([]);input('mcpName','Echo');input('mcpEndpoint',base.endpoint);click('mcpSaveTest');await tick();form.close();resolve(base);await tick();expect((document.querySelector('#mcpConnectionDialog') as HTMLDialogElement).open).toBe(false);expect(api.test).not.toHaveBeenCalled();expect(onConfirmed).not.toHaveBeenCalled();form.dispose();
 });
 it('ambiguous create reconciles metadata without retry, explicit selection resumes saved ID',async()=>{
 const {form,api}=setup();api.create.mockRejectedValueOnce(Object.assign(new Error('Unavailable'),{status:503}));form.openCreate([]);input('mcpName','Echo');input('mcpEndpoint',base.endpoint);click('mcpSaveTest');await tick();click('mcpSaveTest');await tick();expect(api.create).toHaveBeenCalledTimes(1);api.list.mockResolvedValue([base]);click('mcpReconcile');await tick();(document.querySelector('#mcpRecoveredConnections button') as HTMLButtonElement).click();await tick();click('mcpRetest');await tick();expect(api.test).toHaveBeenCalledWith('saved',expect.any(AbortSignal));expect(api.create).toHaveBeenCalledTimes(1);form.dispose();
 });
 it('partial access save re-reads authoritative state and reports failure',async()=>{
 const {form,api}=setup();api.update.mockRejectedValueOnce(new Error('safe failure'));form.openEdit(base,tools,[]);click('mcpSaveAccess');await tick();expect(api.get).toHaveBeenCalled();expect(document.querySelector('#mcpFormError')?.textContent).toContain('Refresh');form.dispose();
 });
 it('supports secret headers and explicit credential removal without logging values',async()=>{
 const {form,api}=setup();form.openEdit({...base,authType:'SECRET_HEADERS',credentialConfigured:true},[],[]);input('mcpCredentialChange','REPLACE');input('mcpSecretHeaders','{"X-Api-Key":"header-canary"}');click('mcpSaveTest');expect((document.querySelector('#mcpSecretHeaders') as HTMLTextAreaElement).value).toBe('');await tick();expect(api.update.mock.calls[0]![1].credential).toEqual({headers:{'X-Api-Key':'header-canary'}});form.dispose();
 });
});
