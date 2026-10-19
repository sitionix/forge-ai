export function escapePointer(value:string):string;
export function getAt(value:any,path:string):any;
export function setAt(value:any,path:string,next:any):any;
export function schemaPaths(schema:any,root?:any,path?:string):Array<{path:string;label:string}>;
export class McpSchemaForm {
 constructor(options:{document:Document;schema:any;value?:any;onChange?:(value:any)=>void;boundPaths?:string[]});
 render(container:Element):void;
 readValue():any;
 dispose():void;
}
