## Event Decision Rules

Always complete the event request object according to the provided OpenAPI completion contract.

Use `required: true` only when event boundary contract work is needed, such as:

- new event topic;
- changed event topic;
- new event payload;
- changed event payload;
- changed producer/consumer contract;
- changed event schema.

Use `required: false` when no event contract work is needed.

When `required: false`:

- provide a clear reason;
- provide a concise summary;
- set `eventName` to an empty string;
- keep `payloadFields` present as an empty array;
- keep `consumers` present as an empty array;
- keep `notes` present as an empty array unless a short clarification is useful;
- do not invent event names;
- do not create placeholder event names;
- do not create placeholder payload fields;
- do not use values like `NOT_REQUIRED`, `NO_EVENT_CONTRACT_CHANGE`, `none`, or fake field names;
- do not omit the event request object.

Do not request event work for purely internal implementation changes.
Expected `required: false` shape:

```json
{
  "required": false,
  "reason": "No event contract work is required for this scope.",
  "scope": "GLOBAL",
  "summary": "No event contract changes are needed.",
  "eventName": "",
  "payloadFields": [],
  "consumers": [],
  "notes": []
}

