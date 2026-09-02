# Service Process Drill-down Design

## Goal

Add an on-demand process drill-down beneath each running systemd service in System Health → Additional info. The drill-down shows only the five highest-resource processes in the selected service's actual cgroup and leaves the existing host and service polling flows unchanged.

## Scope

The feature adds a stateless Agent endpoint, a typed Nexus proxy, and expandable Console rows. It does not add process data to service snapshots, persistence, history, background monitoring, a general command API, process actions, database changes, or changes to the existing four-second service polling lifecycle.

Only one service can be expanded at a time. Process collection occurs only while a service is explicitly expanded; switching or collapsing a service clears its process data.

## HTTP contract and models

The Agent exposes:

`GET /api/v1/projects/{projectId}/ssh-connections/{connectionId}/service-metrics/{unit}/processes?sort=cpu|ram`

The Nexus exposes the corresponding proxy:

`GET /api/v1/infrastructure/agents/projects/{projectId}/ssh-connections/{connectionId}/service-metrics/{unit}/processes?sort=cpu|ram`

`sort` defaults to `cpu`. Values other than `cpu` and `ram` fail through the existing validation behavior. The path variable is decoded by the HTTP framework and forwarded as a typed string; every layer forwards `projectId`, `connectionId`, `unit`, and sort without changing their meaning.

The successful response contains `unit`, `sort`, `sampledAt`, and `processes`. Each process contains:

- `pid`: positive process identifier;
- `process`: nullable command/process name;
- `cpuPercent`: nullable current CPU percentage normalized to total host capacity;
- `rssBytes`: nullable resident memory;
- `threads`: nullable thread count.

The Agent sorts available values descending, places unavailable values last with PID as a deterministic tie-breaker, and returns at most five entries. The Console may defensively limit rendering to five but does not calculate or reinterpret process metrics.

## Agent ownership and unit safety

The application use case reuses the existing `ownedConnection(projectId, connectionId)` path. A missing project, missing connection, or connection belonging to another project fails closed using the current typed not-found behavior. The selected persisted `SshConnection`, including its configured authentication, is passed to the process probe.

The requested unit is data rather than shell source. Before probing, the Agent accepts only a canonical systemd service-unit name: a non-empty unit ending in `.service` and containing only characters valid for a systemd unit identifier. Shell separators, whitespace, control characters, quotes, substitutions, and option-like leading values are rejected. The validated unit is supplied as a positional argument through the existing remote-command quoting infrastructure, never interpolated into the probe script.

## Cgroup membership and process collection

The process-metrics port accepts the owned `SshConnection`, validated unit, and typed sort. Its adapter performs one bounded remote probe:

1. Query systemd for the selected unit's `LoadState`, `ActiveState`, and `ControlGroup`.
2. Treat a missing, unloaded, inactive, or non-running service as the existing typed probe failure. A loaded running service with an empty control group succeeds with an empty process list.
3. Resolve the reported `ControlGroup` beneath the host cgroup filesystem and enumerate `cgroup.procs` in that directory and all descendant cgroups. This includes service child cgroups while excluding every process outside the selected service hierarchy. Membership is never inferred from PID names, executables, command lines, or `MainPID` alone.
4. Capture total host CPU ticks from `/proc/stat` and, for each eligible PID, cumulative user/system ticks from `/proc/{pid}/stat`.
5. Wait for a short fixed bounded sampling interval, then capture the same counters again. Only PIDs still present in the selected cgroup hierarchy are eligible for the final result.
6. Read process name and thread count from the second `/proc/{pid}/stat` sample and RSS from `/proc/{pid}/status`. Per-process disappearance, permission loss, or malformed individual measurements produces `null` for the unavailable field where identity remains trustworthy; it never manufactures zero.

The probe emits a deliberately structured line protocol parsed by the adapter. Command execution failure, malformed probe framing, inability to resolve systemd/cgroup state, or inability to obtain the host CPU sample propagates through the existing typed infrastructure failure path. No live SSH or systemd dependency is used in unit tests.

## CPU and memory semantics

For a process present in both samples:

`cpuPercent = processCpuTickDelta / hostTotalCpuTickDelta * 100`

`hostTotalCpuTickDelta` is the delta of all CPU time fields in the aggregate `cpu` row of `/proc/stat`. This makes 100% represent total host CPU capacity, consistent with the current host/service presentation. CPU is `null` when counters are absent, malformed, decreasing, or the host delta is non-positive. Lifetime `ps %CPU` is never queried or used.

RSS is parsed from `VmRSS` and converted from KiB to bytes with overflow-safe parsing. Thread count comes from the process stat/status data. Missing or malformed RSS and thread measurements remain `null`.

## Nexus mapping-only proxy

Nexus follows the existing SSH metrics proxy structure: typed Agent-client DTOs map to typed Nexus domain models, the application use case executes the client call, and the API mapper produces the typed REST response. Its responsibility is strictly `map → execute → map`.

Nexus performs no sampling, calculation, sorting, truncation, caching, persistence, failure translation, retry policy, or endpoint-specific infrastructure. ForgeIT fixtures verify the public response shape and the exact downstream path and query forwarding, including URL-encoded service units.

## Console lifecycle and presentation

Each service row is selectable and identifies its unit through a data attribute. Clicking a collapsed row:

- clears any previous expanded unit and its state;
- records the selected unit with default sort `cpu`;
- renders an inline detail row directly beneath that service;
- starts exactly one process request for that unit.

Clicking the same expanded service collapses and clears it. Clicking another service immediately closes the previous detail and requests only the new unit. Changing `Top by` to CPU or RAM clears the displayed result and issues a request with the selected sort.

The detail row contains `PID | Process | CPU | RAM | Threads`. It independently renders loading, successful, empty, or error content. A process failure never removes or replaces host/service metrics. Successful rendering is limited to five processes, unavailable values display `—`, and CPU/RAM use the view's existing formatting conventions.

Process requests have their own in-flight token and generation, separate from host and service requests. A request cannot overlap another process request for the same current selection. Each completion must still match the captured project, connection, unit, sort, and generation before changing state. Responses from a collapsed service, prior service, prior connection, prior project, or disposed view are ignored.

No periodic process refresh is required initially; collection is performed on expansion and sort change only. This satisfies on-demand behavior without adding a timer. If refresh is added later, it must remain independently non-overlapping and be stopped by the same clearing lifecycle.

Selecting another SSH connection, loading another project, closing the view, disposing it, or navigating away immediately clears the expanded unit, data, error, and in-flight identity. Existing host polling and four-second service polling continue unchanged.

## Testing

Agent application tests prove the persisted selected connection is passed to the port and cross-project access fails closed. Adapter tests use fixed/fake command output to prove actual requested-cgroup membership, descendant inclusion, unrelated-process exclusion, delta-based CPU calculation, RSS/thread parsing, CPU and RAM Top 5 ordering, deterministic null-last behavior, empty-cgroup success, typed probe failure propagation, and rejection of shell-injection unit values.

Nexus tests cover typed Agent-to-domain-to-REST mapping and exact forwarding of `projectId`, `connectionId`, `unit`, and sort. ForgeIT covers the happy-path response and downstream request shape.

Console tests cover no request before expansion; exact selected-row expansion; second-click collapse; one-open-row behavior; a five-row rendering ceiling; CPU/RAM requests and ordering; loading, error, and empty states; non-overlapping requests; stale-response rejection after service, SSH connection, and project changes; and state/timer cleanup on close, dispose, and navigation.

Targeted Agent, Nexus/ForgeIT, and Console suites run during development. Before completion, the repository's relevant aggregate checks run to detect regressions in existing host and service metrics behavior.

## Deviations

The task's path is preserved and extended with the necessary `sort=cpu|ram` query parameter so the Agent can perform the requested sort before returning Top 5. Process refresh is intentionally event-driven rather than periodic; the requirements permit refresh but do not require it.
