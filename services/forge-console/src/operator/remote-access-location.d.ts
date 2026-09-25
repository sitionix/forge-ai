export function remoteAccessEntry(location: Pick<Location, 'hostname' | 'port' | 'pathname'>):
  {kind:'management'} | {kind:'local-only'} | {kind:'redirect'; url:string};
