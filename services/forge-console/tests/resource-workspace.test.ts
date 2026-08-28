import { describe, expect, it, vi } from 'vitest';
import { JSDOM } from 'jsdom';
import { ProjectWorkspace } from '../src/operator/project-workspace.js';

describe('source-first Project resources', () => {
  it('combines GIT and SSH with minimal rows and no runtime details', () => {
    const dom=new JSDOM('<button id="agentsV2ImportRepository"></button><div id="agentsV2RepositoriesList"></div><div id="agentsV2ProjectTitle"></div><div id="agentsV2ProjectCrumbs"></div><button id="agentsV2CreateAgent"></button><button id="agentsV2CreateWorkflow"></button><button id="agentsV2CreateTask"></button><div id="agentsV2AgentsList"></div><div id="agentsV2WorkflowsList"></div><div id="agentsV2TasksList"></div>');
    const openAsset=vi.fn(); const workspace=new ProjectWorkspace({document:dom.window.document,onOpenAsset:openAsset});
    workspace.render({name:'p'},[{id:'r',name:'repo',cloned:true,git:{branch:'main',workingTree:'CLEAN'}}],[],[],[],[],true,'CURRENT',true,true,true,false,false,null,null,[{id:'a',name:'server'}]);
    const text=dom.window.document.getElementById('agentsV2RepositoriesList')!.textContent!;
    expect(text).toContain('repo'); expect(text).toContain('GIT'); expect(text).toContain('main · Clean');
    expect(text).toContain('server'); expect(text).toContain('SSH'); expect(text).not.toMatch(/RUNNING|STOPPED|services|CPU|RAM/);
    (dom.window.document.querySelector('[data-asset-id]') as HTMLElement).click(); expect(openAsset).toHaveBeenCalledWith('a');
  });
});
