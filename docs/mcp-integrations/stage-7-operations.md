# Stage 7 — catalog Connect

Settings → Integrations → MCP → Add integration shows the official Registry page.
Catalog pages are cached in Agent for **30 days**, bounded to 1,000 entries.
Search, cursor and limit remain distinct keys. Console reuses only its current
loaded page for five minutes. Both caches are in memory; process/page reloads
clear them.
No saved connection or credential is cached by this catalog policy.

Each row has one **Connect** action. It prepares authentication on Agent and opens
the provider's sign-in window directly; it never opens the Custom MCP form.
Allow the popup if the browser blocks it, or reopen a manually closed window with
**Open sign-in** for the same attempt.
Closing the catalog cancels the attempt; it does not replay creation.

Successful sign-in reads the authoritative connection and runs the existing Test.
Connections remain disabled with no approved tools or allowed projects. Configure
access explicitly in connected integration details, then explicitly Enable.

**Add custom MCP** is the separate manual endpoint/credential flow. URL templates
and providers requiring manual credentials give guidance in the catalog row.
They are not silently converted to another form. An uncertain Connect response
requires checking connected integrations before another creation; there is no
automatic POST retry.

## Installation-owned OAuth registration

Agent discovers protected-resource metadata and exact authorization issuer, requires
S256 and HTTPS OAuth endpoints (explicit private HTTP development allowlist only), then chooses an exact issuer's configured Forge client, a supported configured
HTTPS client metadata document, or advertised dynamic client registration (DCR).
All preparation shares `forge.mcp.oauth.discovery-timeout` (default `20s`) and the
existing OAuth HTTP settings. Preparation plus one socket read wait must fit `25s`,
below the normal Nexus → Agent `30s` budget. This does not change tool-call limits.

Optional installed clients use Spring Boot `forge.mcp.oauth.clients` entries:
`issuer`, `client-id`, `client-authentication-method`, `client-secret-file`, `scopes`.
Confidential secrets are existing protected files verified against the runtime UID;
never put their values in YAML, command-line flags, browser storage or catalog DTOs.
An optional `forge.mcp.oauth.client-id-metadata-uri` must be an actual controlled
HTTPS document available to the provider, with the exact client/callback identity.
No registration access-token manager, centralized OAuth service or new database
schema is introduced. DCR client credentials use the existing encrypted connection row.

GitHub Remote MCP does not provide DCR. A registered Forge-owned GitHub App/OAuth
App with the accepted callback and protected client-secret provisioning is
required. That installation prerequisite is not a per-connection end-user form.
The local `sitionix` installation is configured; live Forge verified an OAuth
credential and a successful GitHub MCP Test with 45 discovered tools. The saved
connection remains disabled until the operator approves tools/project access and
explicitly enables it. The external browser consent screen was not independently
observed by this verification; see `stage-7-evidence.md`.

Agent completes preparation before Catalog opens any window. Only the validated
provider authorization URL is opened; there is no local Forge preparation/error
window. Preparation errors remain in the Settings row. If the browser blocks the
provider window, Open sign-in retries opening the same authorization attempt
without replaying Connect. Definitive pre-creation rejection permits explicit Retry;
uncertain outcomes retain reconciliation and never trigger automatic POST replay.
Saved connections, including an existing user's GitHub bearer connection, are never
converted or overwritten by catalog reads or another Connect action.
