# MCP OAuth — registered client

Stage 6 додає browser OAuth до чинного Settings → Integrations → MCP. Це
звичайна можливість Forge: глобального MCP feature switch чи нового Forge login
немає. External provider consent — окрема авторизація доступу до цього provider.

## Connect

1. Вибрати інтеграцію або Add custom MCP та її конкретний HTTP endpoint.
2. Вибрати OAuth. Один раз у Advanced вказати registered client ID,
   issuer, authorization/token endpoints, client authentication method,
   scopes/resource та optional revocation endpoint. Для confidential client
   ввести client secret у write-only поле. Client registration у provider
   має дозволяти точний Forge callback, показаний формою.
3. Натиснути Connect. Forge зберігає disabled connection і відкриває provider
   window. Після consent повернення до Forge запускає чинний Test connection.
4. На disabled connection підтвердити tools/project access. Enable — окрема
   явна дія у details, жоден OAuth/Test/Save не вмикає connection автоматично.

Default callback:
`http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/agents/integrations/mcp/oauth/callback`.
Deployment override Agent `forge.mcp.oauth.callback-uri` і Nexus
`forge.mcp.oauth.browser-origin` має бути узгоджений з фактичною browser origin
та provider registration. Callback не обчислюється з довільного Host/returnUrl.
Default token connect/read timeouts — 2s/5s; transaction TTL — 10m.
Agent `forge.mcp.oauth.transaction-ttl` і Nexus `forge.mcp.oauth.transaction-ttl`
визначають transaction та binding-cookie lifetime відповідно.

## Retry / reconnect / disconnect

Popup blocked: Open sign-in window повторно відкриває поточний transaction,
без повторного create чи автоматичного replay mutations. Cancel/Close залишає
saved connection disabled; cancellation на navigation — best effort, server TTL
обмежує незавершену спробу. Після denied/expired consent можна Reconnect.

Reconnect вимагає попереднього explicit Disable. Чинний registered client secret
залишається encrypted при KEEP; зміна client/config identity потребує явного
credential decision. Новий consent скидає попередні approvals/authorization identity.
Refresh зберігає authorization identity та grants; invalid grant/scope change
вимагає нового consent, tool write автоматично не повторюється.

Disconnect спочатку локально видаляє connection і відкликає grants, потім робить
bounded best-effort provider revocation, якщо provider має configured endpoint.
Невдача provider revocation не відновлює локальний доступ.

## Boundaries / limitations

Provider client secret/access/refresh tokens зберігаються encrypted у чинному
credential row з AES-GCM owner/purpose binding. Runtime отримує execution grant,
а provider bearer додає тільки Agent. Browser storage/public reads не містять
provider tokens. Одноразовий HttpOnly SameSite=Lax cookie прив'язує callback до
browser transaction; це не operator session. Plain localhost HTTP підтримується;
HTTPS cookie використовує Secure. Після callback cookie видаляється, fixed 303
прибирає code/state з result page. Native BroadcastChannel передає лише safe IDs/result;
provider popup не має opener до Forge.

Stage 6 не робить discovery/DCR/CIMD і не вигадує client registration. Live GitHub
потребує зареєстрованого сумісного client/application та callback/permissions.
Детермінований fixture не доводить live provider compatibility. Актуальні
PASS/NOT_VERIFIED межі: [stage-6-evidence.md](stage-6-evidence.md).
