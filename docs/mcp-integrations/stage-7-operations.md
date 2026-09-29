# Stage 7 — catalog Connect

Settings → Integrations → MCP → Add integration shows the official Registry page.
Each row has one **Connect** action. It prepares authentication on Agent and opens
the provider's sign-in window directly; it never opens the Custom MCP form.
Allow the popup if the browser blocks it, using **Open sign-in** for the same attempt.
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
S256, then chooses an exact issuer's configured Forge client, a supported configured
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
App with the accepted callback and protected installation credential provisioning
is still required. That installation prerequisite is not a per-connection end-user
form. No such live Forge App was supplied for this implementation. Live GitHub
OAuth remains **NOT_VERIFIED**; disposable AS acceptance is not provider acceptance.

A safe setup-required row message is preferable to a fake authorization redirect.
Saved connections, including an existing user's GitHub bearer connection, are never
converted or overwritten by catalog reads or another Connect action.
