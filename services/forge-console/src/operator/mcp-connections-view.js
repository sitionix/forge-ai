function text(document,tag,value,className) {
  const node=document.createElement(tag);node.textContent=value;
  if (className) node.className=className;
  return node;
}
export function connectionLabels(connection) {
  const labels=[connection.enabled?'Enabled':'Disabled'];
  if (!connection.checkedAt) labels.push('Not checked');
  if (connection.authType!=='NONE' && !connection.credentialConfigured) labels.push('Credentials required');
  if (!connection.allowedTools.length) labels.push('No approved tools');
  if (connection.projectAccess.scope==='SELECTED' && !connection.projectAccess.projectIds.length) labels.push('No allowed projects');
  return labels;
}
export function renderMcpConnections(container,connections) {
  const document=container.ownerDocument;container.replaceChildren();
  if (!connections.length) { container.append(text(document,'p','No integrations connected'));return; }
  for (const connection of connections) {
    const button=text(document,'button','','mcp-connection-row');button.type='button';button.dataset.connectionId=connection.id;
    const icon=text(document,'span',Array.from(connection.displayName)[0]?.toUpperCase()||'M','mcp-catalog-icon');icon.setAttribute('aria-hidden','true');
    const name=text(document,'span',connection.displayName,'mcp-connection-name');
    const status=text(document,'span',`${connection.enabled?'Enabled':'Disabled'}${connection.checkedAt?'':' · Not checked'}`,'mcp-connection-state');
    const arrow=text(document,'span','›');arrow.setAttribute('aria-hidden','true');button.append(icon,name,status,arrow);container.append(button);
  }
}
export function renderMcpDetails(container,connection,tools,projects) {
  const document=container.ownerDocument;container.replaceChildren();
  const allowed=new Set(connection.projectAccess.projectIds);
  const projectLabel=connection.projectAccess.scope==='ALL'?'All projects':projects.filter(project=>allowed.has(project.id)).map(project=>project.displayName||project.name||project.id).join(', ')||'No allowed projects';
  container.append(text(document,'h3',connection.displayName),text(document,'p',connection.endpoint),text(document,'p',connectionLabels(connection).join(' · ')),
    text(document,'p',`Authentication: ${connection.authType}. ${connection.credentialConfigured?'Credential configured':'No credential configured'}.`),
    text(document,'p',`Last successful check: ${connection.checkedAt||'Not checked'}`),text(document,'p',`Project access: ${projectLabel}`),text(document,'h4','Discovered tools'));
  if (!tools.length) container.append(text(document,'p','No tools discovered. Test connection to refresh inventory.'));
  for (const tool of tools) {
    const approved=connection.allowedTools.some(value=>value.name===tool.name && value.schemaFingerprint===tool.schemaFingerprint);
    container.append(text(document,'p',`${tool.name} — ${approved?'Approved':'Not approved'}${tool.description?`: ${tool.description}`:''}`));
  }
}
