# Event Contract Rules

## Source

Use execution input `tasks` as the event contract intent source.

Preparation is already complete before this step starts.
Do not run repository setup, clone, remote reconfiguration, `checkout develop`, or branch recreation in this step.

Use the source-of-truth event contract repository and paths from the rendered scope context:

- `scope.service.contractRefs.events.sourceRepo`
- `scope.service.contractRefs.events.eventFamily`
- `scope.service.contractRefs.events.serviceCode`
- `scope.service.contractRefs.events.topics`
- `scope.service.contractRefs.events.payloads`

For global lanes, use the matching `scope.relatedServices[*].contractRefs.events` entries.

Resolve the concrete event surface from:

- execution task intent;
- scope context service metadata;
- `contractRefs.events`;
- contract metadata in the configured contract source repository.

Abstract scope names such as `CROSS_SERVICE` are routing hints, not service codes.

## Contract Layout

Event contracts follow the configured event contract repository layout:

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
