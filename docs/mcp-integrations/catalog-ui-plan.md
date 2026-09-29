# Available MCP catalog UI

This is the Settings UI slice of roadmap Stages 8–9 over the accepted Flow 1 backend. It does not complete OAuth, Recommended presets, or all Stage 8–9 acceptance.

1. Add regression coverage for the existing Nexus available endpoint, catalog navigation/search/pagination, cancellation, inert metadata, and prefilled connection form.
2. Add Connected / Catalog navigation using the existing Console design system. Add integration opens Catalog; Add custom MCP opens the existing blank form.
3. Fetch one page through Nexus → Agent → official Registry. Preserve opaque cursors, including empty filtered pages. Keep errors scoped to Catalog and existing connections usable during Registry failure.
4. Reuse the existing form for a selected descriptor. Preserve the endpoint template as metadata; require the operator to enter a resolved endpoint before saving. No provider request or connection mutation occurs on selection.
5. Run focused and full Console tests, typecheck and build. Extend the existing browser smoke; verify built assets against the normal main Nexus when available. Record actual verification boundaries.
6. Review the final diff and stop for review. No PR metadata/comments/reviews or merge.

Existing typed HTTP clients, Registry filtering, Caffeine cache, persistence, provider credentials, runtime grants, and explicit permission/Enable semantics remain unchanged. No new framework, feature flag, authentication flow, scheduler, or catalog persistence.
