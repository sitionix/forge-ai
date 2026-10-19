export class TriggerConfigurationEditor {
 constructor(options:{document:Document;window:Window;api:any});
 open(connection:any,tools:any[],container:Element):Promise<void>;
 close():void;
 dispose():void;
 confirmDiscard():boolean;
}
