export class SettingsPage {
  constructor(options:{document:Document;window:Window;fetcher?:(url:string,init:RequestInit)=>Promise<Response>;runtimeConfig?:unknown});
  mount():this;
  dispose():void;
}
