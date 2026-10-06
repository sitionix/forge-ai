import {contextPathFromLocation} from './infrastructure-http-client.js';

const messages={
  CODEX_AUTH_UNAVAILABLE:'Codex authorization is unavailable. Refresh provider status to retry.',
  CODEX_LOGOUT_FAILED:'Sign-out failed. Codex access is blocked. Refresh provider status before retrying.',
  CODEX_LOGOUT_REQUIRED:'Sign out before signing in again.',
  CODEX_AUTH_CLEANUP_FAILED:'Codex authorization cleanup failed. Refresh provider status before retrying.',
  CODEX_AUTH_PROVIDER_ERROR:'Codex authorization could not be verified. Refresh provider status.',
  CODEX_AUTH_VERIFICATION_FAILED:'Codex authorization could not be verified. Refresh provider status.',
  CODEX_LOGIN_FAILED:'Sign-in was not completed. You can try signing in again.',
  CODEX_LOGIN_NOT_CONFIRMED:'Sign-in was not confirmed. Refresh provider status before retrying.',
  CODEX_LOGIN_CANCEL_FAILED:'Sign-in cancellation failed. Refresh provider status before retrying.',
  CODEX_AUTH_REQUIRED:'Sign in with ChatGPT to authorize Codex.',
  LOGIN_IN_PROGRESS:'A sign-in is already in progress. Refresh provider status.',
  CODEX_LOGOUT_IN_PROGRESS:'Sign-out is in progress. Refresh provider status.',
  LOGIN_NOT_FOUND:'Sign-in expired or is no longer available. Refresh provider status.',
  LLM_BROWSER_DENIED:'This browser sign-in request was rejected. Refresh provider status to retry.',
  INVALID_REQUEST:'The authorization request was rejected. Refresh provider status.',
  INVALID_BROWSER_BINDING:'Refresh provider status before signing in.',
  LLM_INVALID_RESPONSE:'Codex returned an invalid authorization response. Refresh provider status.'
};
export function llmMessage(code) {return messages[code] || messages.CODEX_AUTH_UNAVAILABLE;}
export function validCodexAuthUrl(value) {
  if(typeof value!=='string' || value.length>8192 || /[\u0000-\u0020\u007f-\u009f\\]/.test(value)
    || !/^https:\/\/(?:auth\.openai\.com|auth0\.openai\.com)(?::443)?(?:[/?]|$)/.test(value)) return false;
  try {
    const url=new URL(value);
    return url.protocol==='https:' && ['auth.openai.com','auth0.openai.com'].includes(url.hostname)
      && !url.username && !url.password && !url.hash && !value.includes('#') && (!url.port || url.port==='443');
  } catch(_) {return false;}
}
const nullableText=value=>value===null || typeof value==='string';
const safeCode=value=>value===null || (typeof value==='string' && Object.hasOwn(messages,value));
const onlyFields=(value,fields)=>value && typeof value==='object' && !Array.isArray(value) && Object.keys(value).every(key=>fields.includes(key));
function validProvider(value) {
  return onlyFields(value,['providerId','authState','email','plan','availability','errorCode'])
    && value.providerId==='codex' && ['SIGNED_OUT','CONNECTING','CONNECTED','ERROR'].includes(value.authState)
    && ['AVAILABLE','UNAVAILABLE'].includes(value.availability) && nullableText(value.email)
    && nullableText(value.plan) && safeCode(value.errorCode);
}
function validLogin(value) {
  return onlyFields(value,['loginId','status','expiresAt','authUrl','errorCode'])
    && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.loginId)
    && ['PENDING','COMPLETED','FAILED','CANCELLED','EXPIRED'].includes(value.status)
    && typeof value.expiresAt==='string' && Number.isFinite(Date.parse(value.expiresAt)) && safeCode(value.errorCode)
    && (value.status==='PENDING'?validCodexAuthUrl(value.authUrl):value.authUrl===null);
}

export class LlmApi {
  constructor({fetcher=globalThis.fetch.bind(globalThis),location=globalThis.location}={}) {
    this.fetcher=fetcher;
    this.base=`${contextPathFromLocation(location)}/api/v1/infrastructure/agents/integrations/llm`;
  }
  failure(status,code) {
    const safe=Object.hasOwn(messages,code)?code:'CODEX_AUTH_UNAVAILABLE';
    return Object.assign(new Error(llmMessage(safe)),{status,code:safe});
  }
  async request(method,path,signal,validate) {
    if(signal?.aborted) throw new DOMException('Request cancelled','AbortError');
    const mutation=method==='POST' || method==='DELETE';
    const headers={Accept:'application/json'};
    if(mutation) headers['Content-Type']='application/json';
    let response;
    try {
      response=await this.fetcher(this.base+path,{method,headers,body:mutation?'{}':undefined,signal,
        credentials:'same-origin',cache:'no-store',mode:'same-origin',redirect:'error'});
    } catch(error) {
      if(signal?.aborted || error?.name==='AbortError') throw new DOMException('Request cancelled','AbortError');
      throw this.failure(503,'CODEX_AUTH_UNAVAILABLE');
    }
    if(signal?.aborted) throw new DOMException('Request cancelled','AbortError');
    let value;
    try {value=await response.json();} catch(_) {throw this.failure(502,'LLM_INVALID_RESPONSE');}
    if(signal?.aborted) throw new DOMException('Request cancelled','AbortError');
    if(!response.ok) throw this.failure(response.status,value?.code);
    if(response.status!==200 || !validate(value)) throw this.failure(502,'LLM_INVALID_RESPONSE');
    return value;
  }
  providers(signal) {return this.request('GET','/providers',signal,value=>Array.isArray(value) && value.length===1 && value.every(validProvider));}
  startLogin(signal) {return this.request('POST','/codex/login',signal,validLogin);}
  login(id,signal) {return this.request('GET',`/codex/logins/${encodeURIComponent(id)}`,signal,validLogin);}
  cancelLogin(id,signal) {return this.request('DELETE',`/codex/logins/${encodeURIComponent(id)}`,signal,value=>validLogin(value) && value.status!=='PENDING');}
  logout(signal) {return this.request('POST','/codex/logout',signal,validProvider);}
}
