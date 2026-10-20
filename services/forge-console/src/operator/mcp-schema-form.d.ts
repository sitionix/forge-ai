export function escapePointer(value:string):string;
export function getAt(value:any,path:string):any;
export function setAt(value:any,path:string,next:any):any;
export function pointerAfterArrayRemoval(pointer:string|null,arrayPath:string,index:number):string|null;
export function schemaPaths(schema:any,root?:any,path?:string):Array<{path:string;label:string}>;
export class McpSchemaForm {
 constructor(options:{document:Document;schema:any;value?:any;onChange?:(value:any)=>void;onArrayItemRemoved?:(path:string,index:number)=>void;boundPaths?:string[]});
 render(container:Element):void;
 readValue():any;
 dispose():void;
}
