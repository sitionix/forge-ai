const query=new URLSearchParams(window.location.search);
window.history.replaceState(null,'',window.location.pathname);
const uuid=value=>typeof value==='string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const transactionId=query.get('transactionId'),connectionId=query.get('connectionId');
const known=[...query.keys()].every(key=>['transactionId','connectionId','result'].includes(key));
const connected=known && uuid(transactionId) && uuid(connectionId) && query.get('result')==='connected';
document.getElementById('oauthResultTitle').textContent=connected?'Signed in':'Sign-in not completed';
document.getElementById('oauthResultMessage').textContent=connected?'Forge is checking available tools. You can close this window.':'Return to Forge and choose Connect to try again.';
if(known && uuid(transactionId) && window.BroadcastChannel) {
  const channel=new BroadcastChannel(`forge-mcp-oauth-${transactionId}`);
  channel.postMessage(connected?{transactionId,connectionId,result:'connected'}:{transactionId,result:'failed'});
  channel.close();window.close();
}
