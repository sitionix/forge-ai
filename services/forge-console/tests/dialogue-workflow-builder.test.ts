import { describe, expect, it, vi } from 'vitest';
import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';
import { WorkflowBuilder } from '../src/operator/workflow-builder.js';
describe('Dialogue builder',()=>{
 it('creates GLOBAL agent-targeted dialogue with explicit dispositions and round trips the schema',async()=>{
  const dom=new JSDOM(readFileSync('src/operator/agent-projects.html','utf8'),{url:'http://localhost'});
  const builder:any=new WorkflowBuilder({document:dom.window.document,window:dom.window as any,api:{listProjectRepositories:vi.fn(async()=>[])}});
  builder.open({id:'w',name:'Workflow',nodes:[],connections:[]},{id:'project',name:'Project'},[{id:'agent',name:'Groomer'}]);
  expect(dom.window.document.querySelector('[data-add-dialogue-agent-id="agent"]')).not.toBeNull();
  builder.addDialogueNode('agent');const node=builder.workflow.nodes[0];expect(node).toMatchObject({nodeType:'DIALOGUE',targetId:'agent',scopeMode:'GLOBAL',contextMode:'DIALOGUE_WITHIN_NODE_RUN'});
  expect(node.outputs.map((p:any)=>p.dialogueDisposition)).toEqual(['ACCEPT','REWORK','DEFER']);
  builder.openNodeEditor(node.id);expect(dom.window.document.querySelector('[data-node-editor-context-mode]')).toBeNull();
  expect(dom.window.document.querySelector('option[value="PER_SCOPE"]')).toBeNull();
  builder.saveNodeEditor();expect(builder.workflow.nodes[0].outputs.map((p:any)=>p.dialogueDisposition)).toEqual(['ACCEPT','REWORK','DEFER']);
  builder.nodeEditorDraft=builder.workflow.nodes[0];builder.nodeEditorDraft.outputs[1].dialogueDisposition='ACCEPT';
  expect(builder.validateNodeEditorDraft()).toContain('ACCEPT');await Promise.resolve();builder.close();dom.window.close();
 });
});
