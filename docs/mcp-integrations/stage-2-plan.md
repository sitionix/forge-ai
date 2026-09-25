# Stage 2 — історичний загальний план

Цей план передував Flow 1/Flow 2 поділу та рішенню про SDK 0.18.4 без DNS pinning. Фактичні backend зрізи Test/inventory та internal tool-call boundary і їхні межі описані в [stage-2-backend-plan.md](stage-2-backend-plan.md) та [stage-2-evidence.md](stage-2-evidence.md). DNS rebinding нижче не позначено виконаним.

Починати лише після завершення й прийняття Stage 1 та окремої вказівки користувача. Stage 1 не робить зовнішніх MCP calls.

1. Зафіксувати сумісний MCP SDK із Stage0 evidence (0.18.3, Java21 / Boot3.3.4 / Jackson2) та protocol matrix. Повторити SDK compatibility probe, якщо версії змінилися. Додати infrastructure adapter у чинному Agent; SDK types і довільний protocol JSON не виходять у domain/Nexus API.
2. Перед першим outbound call реалізувати єдину endpoint policy: schemes, no userinfo/credential query, resolved IPv4/IPv6, явні private host/port allowances, link-local/metadata denial, DNS rebinding і redirect revalidation. Не переносити credentials на інший origin; TLS verification обов’язкова.
3. Додати bounded initialize + paginated tools/list з protocol/capability handling, timeout/response/inventory limits. Probe не викликає tool. 401,403,unsupported protocol і transport failure мають різні безпечні результати.
4. Зберігати inventory/schema fingerprint; додати явне підтвердження tool allowlist і invalidation при schema/endpoint/auth change. Server descriptions є даними. Наявна порожня allowlist не стає дозволом автоматично.
5. Розширити існуючу typed management вертикаль для Test connection та tool summaries через ті самі session/CSRF/service guards. Не додавати direct Console→Agent API.
6. Додати internal typed invocation boundary для наступного gateway: зберігати MCP protocol failure, tool isError та structured result окремо. Gateway, runtime grants, Codex injection, OAuth, каталог та нова Settings UI залишаються поза Stage2.
7. Детерміновані disposable HTTP fixtures із synthetic credentials: no-auth/bearer/headers, pagination/empty/duplicate/changed inventory, malformed/oversized payload, timeout,401/403, TLS та SSRF/redirect/DNS/header-leak negatives. ForgeIT перевіряє typed forwarding і zero upstream при локальній відмові. Без live provider у CI.

Вихід: окреме Stage2 evidence із версіями, точними командами, результатами й NOT_VERIFIED. HTTP200 не є MCP readiness. Жоден пункт цього плану не виконано в Stage1.
