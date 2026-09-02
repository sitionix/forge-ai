# Service Resource Usage Design

## Goal

Add live resource usage for running systemd services to System Health → Additional info for the selected saved SSH connection.

## Scope

The feature adds one stateless Agent endpoint and its typed Nexus proxy, then renders the samples in the existing Console System Health view. It does not persist samples, add monitoring/history, expose arbitrary commands, alter Project Logs/SSE, or add top-process data.

## API and domain model

The Agent exposes:

`GET /api/v1/projects/{projectId}/ssh-connections/{connectionId}/service-metrics`

The response is a snapshot with `sampledAt` and a `services` collection. Each service contains:

- `unit`: systemd service unit name;
- `description`: nullable systemd description;
- `cpuUsageNanos`: nullable cumulative CPU time for the whole service cgroup;
- `memoryBytes`: nullable current memory for the whole service cgroup;
- `tasks`: nullable current task count for the whole service cgroup.

Unavailable measurements are represented by `null`, never a manufactured zero. The model is duplicated only at transport boundaries where the existing Agent/Nexus architecture requires typed DTOs; domain types remain explicit.

## Agent ownership and collection

`SshConnectionUseCases.serviceMetrics(projectId, connectionId)` first verifies that the project exists, then loads the saved `SshConnection` and accepts it only when its `projectId` equals the requested project. Missing projects and missing/cross-project connections fail closed using the existing not-found error conventions.

A dedicated service-metrics port accepts the resolved `SshConnection`. Its CLI adapter uses the existing typed SSH execution and quoting infrastructure. It discovers running systemd units with `systemctl`, restricted to service units in running state, and obtains systemd properties for those units in a batched invocation where supported. The properties are `Id`, `Description`, `CPUUsageNSec`, `MemoryCurrent`, and `TasksCurrent`. These systemd values describe the service cgroup rather than only `MainPID`.

The adapter parses known unavailable systemd sentinel values and malformed/missing measurements as `null`. A failure to discover or query services is surfaced through the existing typed runtime-command failure path. An empty set of running services is a successful empty snapshot.

## Nexus proxy

Nexus extends the existing SSH-connection proxy flow with a typed domain method, client DTOs, mapper, HTTP client method, adapter method, use case, and REST response. Both identifiers are forwarded unchanged. Nexus does not reinterpret, cache, or persist the sample.

The external Nexus path follows the existing infrastructure-agent route:

`GET /api/v1/infrastructure/agents/projects/{projectId}/ssh-connections/{connectionId}/service-metrics`

## Console state and CPU calculation

The Console adds `getSshConnectionServiceMetrics(projectId, connectionId)` to the existing projects API. `SystemHealthView` owns a second polling lifecycle independent of host-metrics polling, with a default interval of 4000 ms.

For each service present in consecutive snapshots, current CPU percentage is:

`max(0, cpuDeltaNanos) / elapsedNanos / logicalCpuCount * 100`

The host logical CPU count comes from the existing host sample's per-core CPU array. Therefore 100% represents total host capacity. CPU is unavailable when there is no prior compatible sample, either timestamp or cumulative value is unavailable/non-monotonic, elapsed time is non-positive, or host CPU count is unavailable.

Service state is cleared immediately on SSH connection changes. Each request captures the current generation, project, and connection and is ignored if any changed before completion. At most one service request is active for a generation. Timers are cleared on connection changes, close, dispose, and navigation through the view's existing lifecycle.

After a later polling failure, the last successful service values remain visible with a stale indication and polling retries. An initial failure displays an unavailable state inside Additional info without hiding host metrics.

## Presentation

Additional info contains a compact table with columns `Service | CPU | RAM | Tasks`. The default view sorts by current CPU descending and shows the top three running services. CPU values unavailable on the first snapshot sort after available values. Description appears as secondary text under the unit when present.

`Show more` expands the table to all running services and becomes `Show less`. The expanded view offers sorting by CPU, RAM, and Name. CPU and RAM sorts place unavailable measurements last; Name is ascending by unit. RAM is formatted in human-readable MB/GB and includes a percentage when the existing host snapshot provides positive total RAM. Tasks and all unavailable values display `—`.

## Testing

Agent tests cover project/connection ownership, running-service-only collection, whole-cgroup property parsing, nullable unavailable values, and command construction. Nexus unit and ForgeIT tests prove typed mapping plus unchanged `projectId`/`connectionId` forwarding and response shape.

Console tests cover CPU delta normalization and unavailable cases, default top three, expansion/collapse, CPU/RAM/Name sorting, descriptions and unavailable rendering, independent non-overlapping polling, stale values after failure, stale-response rejection, immediate clearing on connection switch, and timer disposal. Relevant existing Agent, Nexus, and Console suites are run before completion.
