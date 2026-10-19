import { contextPathFromLocation } from './infrastructure-http-client.js';

export function mountColdRemoteAccess({document,window,fetcher=window.fetch.bind(window),delay=ms=>new Promise(resolve=>window.setTimeout(resolve,ms))}) {
  const section=document.getElementById('remoteCold');
  const progress=document.getElementById('remoteColdProgress');
  const error=document.getElementById('remoteError');
  const buttons=['remoteColdGive','remoteColdConnect'].map(id=>document.getElementById(id));
  const endpoint=`${contextPathFromLocation(window.location)}/api/v1/infrastructure/agents/remote-access/bootstrap`;
  let pending=false;
  section.hidden=false;
  const state=async () => {
    const response=await fetcher(endpoint,{method:'GET',cache:'no-store',credentials:'same-origin',redirect:'error'});
    if(!response.ok) throw new Error('Local Remote Access setup is unavailable.');
    return response.json();
  };
  const start=async intent => {
    if(pending) return;
    pending=true;error.hidden=true;buttons.forEach(button=>{button.disabled=true;});
    progress.textContent='Preparing SSH… first setup can take up to 30 minutes.';
    try {
      let current=await state();
      if(current.status!=='READY') {
        const response=await fetcher(endpoint,{method:'POST',headers:{'X-CSRF-TOKEN':current.csrfToken,'Content-Type':'application/json'},
          cache:'no-store',credentials:'same-origin',redirect:'error',body:'{}'});
        if(!response.ok) throw new Error('Local Remote Access setup could not start.');
        // The on-demand systemd setup may spend up to 30 minutes preparing its first rootfs.
        const started=Date.now(), deadline=started+1860000;
        while(Date.now()<deadline) {
          await delay(1500);
          current=await state();
          progress.textContent=`Preparing SSH… ${Math.floor((Date.now()-started)/60000)} min elapsed; first setup can take up to 30 minutes.`;
          if(current.status==='FAILED') throw new Error('SSH preparation failed on this machine. Check Forge setup and retry.');
          if(current.status==='READY') break;
        }
        if(current.status!=='READY') throw new Error('SSH preparation status is still unconfirmed. Refresh to check it before retrying.');
      }
      const target=`http://127.0.0.1:9100${contextPathFromLocation(window.location)}/operator/remote-access.html#${intent}`;
      window.location.assign(target);
    } catch (failure) {
      error.hidden=false;error.textContent=failure.message || 'Remote Access preparation failed.';
      progress.textContent='Preparation is incomplete.';
      pending=false;buttons.forEach(button=>{button.disabled=false;});
    }
  };
  buttons[0].addEventListener('click',()=>start('give'));
  buttons[1].addEventListener('click',()=>start('connect'));
  return {start};
}
