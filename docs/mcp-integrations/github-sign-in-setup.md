# Forge GitHub sign-in: installation registration

Status: GitHub App registered and installed for the local `sitionix/forge-ai`
installation on 2026-09-30. Forge then persisted an OAuth credential and a
successful live GitHub MCP check with 45 tools. The browser consent screen was
not independently observed. This is a one-time Forge application setup, not a
form shown when an end user connects an MCP integration.

The owner is the personal GitHub account `sitionix` (repository owner of
`sitionix/forge-ai`), not a GitHub organization. Register a dedicated **GitHub
App** under that account; do not reuse the `gh` CLI authorization.

The catalog Connect action must open the provider authorization URL after Agent
has prepared a valid authorization request. No Forge preparation/error page is
opened in a separate window. Preparation errors stay in the existing Settings row.
The existing blocked-popup action reopens the same attempt without replaying Connect.

Registration values for the supported local installation:

| Field | Value |
|---|---|
| Application name | Forge AI MCP sitionix |
| Homepage | https://github.com/sitionix/forge-ai |
| Authorization callback | http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/agents/integrations/mcp/oauth/callback |
| Issuer | https://github.com/login/oauth |
| Authorization endpoint | https://github.com/login/oauth/authorize |
| Token endpoint | https://github.com/login/oauth/access_token |
| MCP resource | https://api.githubcopilot.com/mcp/ |
| OAuth flow | Authorization code with state and S256 PKCE |
| Webhooks | Disabled; Forge does not consume GitHub webhooks |
| Initial repository permissions | Contents, Issues and Pull requests: read-only; Metadata: read-only (GitHub default) |
| Availability | Any account; repository access is selected during installation |

The [pre-filled GitHub App registration form](https://github.com/settings/apps/new?name=Forge+AI+MCP+sitionix&description=Forge+AI+connection+to+the+remote+GitHub+MCP+server&url=https%3A%2F%2Fgithub.com%2Fsitionix%2Fforge-ai&callback_urls%5B%5D=http%3A%2F%2F127.0.0.1%3A9099%2Ffgaisox%2Fapi%2Fv1%2Finfrastructure%2Fagents%2Fintegrations%2Fmcp%2Foauth%2Fcallback&public=true&webhook_active=false&contents=read&issues=read&pull_requests=read)
must be reviewed and submitted while signed in as `sitionix`. Keep expiring
user access tokens enabled. Do not enable "Request user authorization (OAuth)
during installation": Forge creates the OAuth transaction when Connect is
clicked, not during a separate installation. Install the App on `sitionix`
with only the repositories it should access. The requested read-only
permissions can be expanded later through GitHub's normal App review flow.

Issuer/endpoints/S256 were read from GitHub's live authorization-server metadata
on 2026-09-29. Protected-resource metadata lists that exact issuer. Neither
metadata document advertises dynamic registration or client-ID metadata support.
A registered Forge client identity is required. The authenticated GitHub App
API verified the slug, personal owner `sitionix`, and Client ID match. The
repository installation API verified selected access to `sitionix/forge-ai`.

Use the existing `forge.mcp.oauth.clients` Spring properties for the installed
client; the installation credential remains a protected file verified against the
runtime UID. Never place its value in source, command arguments, URLs, browser
storage, logs or chat. Do not borrow the GitHub CLI client ID, app identity or token.
No dummy client ID/secret is a valid substitute for registration.

For the normal main Agent, `oauth-clients.env` in `/etc/forge-ai/mcp` contains
only the issuer, Client ID, `client_secret_post`, and the path to a separate
Client secret file. Both local files are mode `0600` and owned by the Agent
control UID. The optional systemd `EnvironmentFile` is on `forge-agent.service`
only; the normal installer does not rewrite either file. Preservation across a
subsequent full `just start` is **NOT_VERIFIED**. The downloaded GitHub App private-key
PEM is mode `0600` and is not used for end-user OAuth Connect.

After external registration, configure the exact issuer above with the App's
client ID, `client_secret_post`, and a protected client-secret file. GitHub
authorization-server metadata omits `token_endpoint_auth_methods_supported`;
Forge accepts an explicitly configured installed-client method in that case.
GitHub Apps do not use OAuth scopes: leave installed-client scopes empty.
Protected-resource `scopes_supported` are advertised choices, not a request for
all scopes. A subsequent live Forge connection read confirmed an OAuth
credential and a successful saved connection check for GitHub; its inventory
contained 45 tools. The browser consent screen was not independently observed.
The saved connection remains disabled with no approved tools or allowed projects.
Existing generic OAuth error/refresh/reconnect handling remains unchanged.

Live acceptance now confirms the authoritative connection read, configured OAuth
credential, successful saved Test, and inventory. Browser-level observation of
the external consent screen is not separately verified. Runtime tool use is not
verified and requires explicit permission editing/Enable; no synthetic fixture
or safe 409 error screen was counted as a successful connection.

References:
- https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps
- https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app
- https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app
- https://docs.github.com/en/apps/creating-github-apps/writing-code-for-a-github-app/building-a-login-with-github-button-with-a-github-app
- https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md
