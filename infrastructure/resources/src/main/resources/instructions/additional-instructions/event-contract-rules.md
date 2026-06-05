# Event Contract Rules

## Source

Use execution input `tasks` as the event contract intent source.

Use source-of-truth event contracts under:

`app-afesox/apis/<service-code>/event`

Resolve the concrete event surface from:

- execution task intent;
- scope context service metadata;
- `contractRefs.events`;
- `app-afesox/apis/metadata.yml`.

Abstract scope names such as `CROSS_SERVICE` are routing hints, not service codes.

## Contract Layout

Event contracts follow the app-afesox event layout:

- `apis/<service-code>/event/<event-name>/<version>/envelope.avsc`;
- `apis/<service-code>/event/<event-name>/<version>/imports/<EventName>.avsc`;
- `apis/<service-code>/event/metadata.yml`;
- `apis/<service-code>/event/asyncapi.yml`;
- shared metadata schema in `apis/common/event/Metadata.avsc`.

## Work

Apply required event contract changes only inside the resolved event surface:

- Avro envelope;
- Avro payload/import schemas;
- AsyncAPI channel/message definitions;
- event metadata;
- producer/consumer tag registration;
- top-level `apis/metadata.yml` event entries when a new generation target is required.

Do not implement runtime publishing, consuming, outbox processing, retries, or service code in this lane.

## Result

Keep these facts for later steps:

- affected service code;
- event name and version;
- event tag;
- producer/consumer direction;
- changed contract files;
- generation target names from metadata;
- consumers mentioned by the task.
