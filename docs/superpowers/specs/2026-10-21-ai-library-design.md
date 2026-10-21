# AI Library: project-aware runtime inventory

Status: implemented. Final evidence and limits: `docs/ai-library-runtime-evidence.md`. Historical inspection findings below describe the design stage; final runtime findings are recorded in that evidence.

## Intent and constraints

Implement one read-only effective library for the current Forge project and the
Knowledge Active Profile. No second provider selector, inventory persistence,
Global/Local views, browser-supplied paths, or native artifact execution.
Forge Agents and Workflows remain separate bounded contexts.

## Repository evidence

- Examined main at `d94f5d5b`; remote main matched that revision.
- No assigned task identifier was supplied. Inspected local and remote feature
  refs; highest identifier was 165. Created regular branch `feature/SITIONIX-166`.
- Existing untracked `tools/` is unrelated and must remain untouched.
- Nexus exposes project operations below
  `/api/v1/infrastructure/agents/projects/{projectId}`; Agent uses
  `/api/v1/projects/{projectId}`.
- Nexus already has `KnowledgeActiveProfileClient`, `GetActiveProfileUseCase`,
  and an ActiveProfile with revision and typed LLM display metadata.
- Agent owns `LocalProjectWorkspacePort` and protected workspace resolution.
- `CodexAppServerClient.request` intentionally permits only `model/list`.
  Its existing process ownership, authorization lease, cancellation and cleanup
  are reusable; its execution flow must not be invoked for discovery.
- `DefaultCodexAppServerProcessStarter` delegates to the managed runtime launcher.
  The launcher isolates UID, HOME, configuration, plugins and credentials.
- `CodexMcpInventoryVerifier` checks thread-scoped MCP inventories against issued
  grants. It is not a general project-library discovery API.
- Project has no owner attribute. Existing management authentication and project
  validation are the current access model; do not claim per-user ownership ACLs.
- Console has ProjectDashboardView, RequestCoordinator, hash navigation and
  PollingCoordinator patterns to reuse.

## Protocol findings and verification limits

Generated schema from PATH-installed Codex 0.163.0 using a temporary CODEX_HOME;
no app-server conversation or skill/tool execution was started.

- `skills/list`: accepts `cwds` and `forceReload`; returns per-CWD skills and
  errors. Metadata includes path, scope, enabled state and optional plugin ID.
  The response is not paginated.
- No native agent or flow list operation was found in generated ClientRequest.
- No general native tool list operation was found. `mcpServerStatus/list`
  exposes MCP tools, supports cursor/limit and optional thread context, and must
  not be presented as a complete built-in tool inventory.
- Native skill precedence is not established by this schema. Do not invent
  name-based precedence. Verify managed-version behavior using deterministic
  duplicate-name fixtures before implementing any suppression.
- The managed runtime may use a different binary. Reading
  `/etc/forge/runtime-launcher.json` failed with OS permission denied, including
  outside the sandbox. Managed protocol and live inventory remain unverified.

## Approach

Recommended: Nexus composes a selected-profile snapshot and calls a typed Agent
query. Agent validates project identity, resolves managed workspace context and
dispatches to provider adapters through four distinct category ports. A
request-scoped provider inventory session shares authorization/process lifecycle
across supported category queries. Codex JSON remains inside infrastructure.

Rejected alternatives: Agent calling Knowledge couples bounded contexts;
Console composing runtime calls exposes orchestration to browser races;
filesystem scanning duplicates provider resolution and widens access.

## Domain and application contracts

Define typed LibraryProviderContext, LibraryContext, LibrarySnapshot,
InventoryResult<T>, SkillDescriptor, AgentDescriptor, FlowDescriptor and
ToolDescriptor. Category ports each expose one read-only discover operation.
Provider support registration is explicit, with no Codex conditionals in Console.
Do not wrap arbitrary JSON-RPC calls in a public generic inventory interface.

InventoryResult includes items, status and typed sanitized diagnostics. Statuses:

| Status | Meaning |
| --- | --- |
| AVAILABLE | Supported query returned usable entries |
| EMPTY | Supported query completed successfully with no entries |
| UNSUPPORTED | Provider has no supported operation for this category |
| UNAVAILABLE | Runtime, authorization or required safe context unavailable |
| FAILED | Supported discovery failed or returned an invalid response |

Partial entries may accompany FAILED with an explicit incomplete diagnostic;
their size must not be presented as a complete Dashboard count. Failure of one
category preserves other category results.

Validate project before creating workspace or launching runtime. Reuse current
access checks; paths are resolved exclusively from trusted project UUIDs. Include
only explicitly managed repository workspaces if native skill discovery requires
them; enumerate them through existing repository/workspace ports, never scans.
Apply bounds to workspace count, items, text length, pages and total deadline;
report incomplete results explicitly rather than silently truncating.

## Provider context and stale responses

Resolve Knowledge Active Profile once per inventory snapshot in Nexus; forward
its immutable revision/provider/model/effort to Agent through a typed server call.
Neither Agent model settings nor browser parameters select the library provider.
Return profile revision with every snapshot and use no-store response headers.
Missing profile returns an explicit unavailable snapshot after project validation.

Console clears results immediately on project/profile invalidation. Reuse
RequestCoordinator generation guards and AbortController. Publish profile-change
invalidation after successful settings updates, including cross-tab notification;
revalidate the profile when the page regains focus and while the library is visible
using bounded lightweight polling. A separate profile-revision observation must
invalidate an in-flight inventory response even when the project is unchanged.
Profile checks are outside snapshot resolution and never launch a provider.
Remote changes become observable on notification or the next profile check; no
instantaneous cross-client guarantee is claimed without a server event channel.

## Codex implementation and security

Add a narrow approved inventory session boundary; keep the existing model/list
restriction intact. Reuse managed starter, transport, initialization and
ForgeCodexAuthorizationGate. Register cancellation before touching process streams;
close transport and release lease on every exit, including parsing failures.
One session per snapshot, no process per category and no indefinite session cache.
No thread/start, turn/start, tool calls, shell commands or skill execution.

Skills discovery uses explicit trusted working directories (serialized as `cwds`) and the actual
project working directory. Let the runtime resolve its allowed global/local
sources. Keep distinct resources with the same name. Deduplicate only identical
provider resource identities across repeated CWD results. Preserve source scope
and provider identity internally. Use stable opaque IDs derived from provider,
category and native identity, never names alone; do not serialize source paths.

Agents and Flows return UNSUPPORTED when the managed protocol confirms no native
operation. For Tools distinguish absent generic inventory support from supported
MCP inventory. MCP discovery is allowed only if the existing approved project
selection/grant contracts can supply an authorized read-only inventory without
execution or new approvals. Otherwise expose UNAVAILABLE with a safe-context
diagnostic, explicitly describing the MCP limitation; never label a supported
operation UNSUPPORTED merely because grants are unavailable. No substitution of
Forge database tools as native runtime capabilities.

Recheck metadata provenance against managed allowed roots; reject unsafe paths
and symlink escapes without reading their contents. No new roots or relaxed
sandbox. API DTOs allowlist safe presentation fields, sanitize descriptions and
diagnostics, omit credentials/config/dependencies/raw errors/transport metadata,
and render all provider text as text in Console.

## API and UI

Public GET `/api/v1/infrastructure/agents/projects/{projectId}/ai-library`.
Use typed Nexus client DTOs and existing error conventions; authenticated internal
Agent query receives provider context from Nexus, never from browser query params.
Verify the internal route cannot bypass existing management authorization.

One Dashboard card: AI Library, description
`Capabilities available to the active AI provider`. Show provider and complete
supported category counts from the same snapshot; EMPTY may show zero, other
non-success states show labels. One snapshot on overview entry is justified by
requested summary data; reuse it for immediate library navigation, refresh on
profile/project changes, and do not repeatedly launch runtime on dashboard render.

Page `#/projects/{projectId}/ai-library`: read-only provider/model, search,
All/Skills/Agents/Flows/Tools navigation, status-specific messages and Refresh.
Item identity in a hash-compatible detail route supports direct links and
Back/Forward. Load details from the snapshot, use bounded/paged rendering for
large collections, show unobtrusive safe provenance and no Create controls.
Update Dashboard rendering to support a card without a create action safely.

## Validation and review requirements

Before implementation, verify managed binary protocol and duplicate-name
resolution using safe fixtures. If access remains unavailable, keep live
verification NOT_VERIFIED and test supported behavior with deterministic fixtures.

Tests cover profile snapshot/revision races, missing profile, project access and
isolation, global/local union and native precedence evidence, stable distinct
identities, all statuses, partial failures, bounded responses, auth revocation,
cleanup, no execution, metadata redaction and symlink rejection. Nexus tests
verify typed composition and browser input cannot select provider or CWD.
Console tests cover card counts, filtering, large inventories, details, refresh,
navigation cancellation, provider changes, direct links and history.

Run Codex adapter, Agent domain/application/integration, Nexus client/API,
Console tests/typecheck/build, runtime launcher tests if changed, and
`git diff --check`. Review architecture, ownership, provider neutrality, auth,
lifecycle and regression risks independently after implementation. Report browser
and managed-runtime smoke only when actually performed. No push, merge or PR edits.

## Implementation status

Design and execution plan were approved. Implementation and independent review
are complete; consult `docs/ai-library-runtime-evidence.md` for actual supported
operations, verification scope, operational prerequisites and limitations.
