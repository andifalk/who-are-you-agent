# Travel Agent — Who Are You, Agent? (DevFest 2026)

Java 25 / Maven / Spring Boot **4.1.2** / Google ADK for Java **1.14.0** /
Gemini / Keycloak (`corporate` + `travel`) / two MCP Streamable HTTP clients.

## Implementation Status

The agent implements browser OIDC login, per-session mode selection, Client
Credentials, delegated Token Exchange/JWT Bearer Grant, request-local MCP
clients, sanitized error diagnostics, and Inspector events. Overprivileged
and Delegated demos have been exercised in the configured environment;
the confirmed model is `gemini-3.1-flash-lite` with ADK 1.14.0.

The code and `.env.example` files still default to `gemini-2.5-flash`:
set `GEMINI_MODEL` explicitly for the intended deployment. Model access and
billing belong to the Google project, not the MCP servers.

**Google Agent Identity remains unimplemented and fails closed.** Selecting
that mode produces an error; neither a header assertion nor a service-account
ID token is treated as a verified agent identity.

## Web UI and Session Model

The app uses a Spring MVC browser BFF with an HttpOnly session cookie and CSRF
protection. Tokens stay server-side. Agent mode is stored per browser session;
`POST /api/mode` changes it and clears MCP token caches. `GET /api/session`
shows the active mode. The runner and MCP toolsets are new per chat request,
so LLM conversation history is not persisted between requests.

## Prerequisites

- JDK 25 and Maven for local builds; Docker Compose for container deployment.
- Reachable Keycloak with `corporate` and `travel` realms, Token Exchange and
  JWT Bearer Grant support enabled as required by the installed version.
- Corporate confidential client (`CORPORATE_CLIENT_ID`) with browser login,
  correct redirect URI, and permission for the configured token exchange.
- Travel confidential client (`TRAVEL_GRANT_CLIENT_ID`) authorized for the
  JWT Bearer Grant, with Corporate issuer trust and user mapping.
- Travel service-account client (`INSECURE_CLIENT_ID`) with Client Credentials
  and all demo scopes, including `expenses.approve`.
- Resource-specific audience mappers and scope assignments, described in the
  [working Keycloak configuration](../deploy/README.md#working-audience-mappers-for-travel-and-expense-mcp).
- Gemini key, an available `generateContent` model, and matching billing setup.

The configured browser registration requests `openid profile email access-travel`.
`EXCHANGE_SCOPE` configures the subsequent exchange request separately; its
fallback and the environment templates currently use `access-travel-domain`.
Ensure Keycloak permits the actual login and exchange scopes. If changing the
browser login scope itself, update `spring.security.oauth2.client.registration`
in `application.yml`; changing `EXCHANGE_SCOPE` alone does not change it.

The Travel tools use `@ToolParam`, while Expense uses `@McpToolParam`.
The expected schemas are:

| MCP | Tool | Inputs |
|---|---|---|
| travel | `search_flights` | `origin`, `destination`, `date` (ISO) |
| travel | `book_flight` | `flightId`, `traveler`, `date` (ISO) |
| travel | `get_booking` | `bookingId` |
| expense | `create_expense` | `bookingId`, `amount` (EUR) |
| expense | `get_expense` | `expenseId` |
| expense | `approve_expense` | `expenseId` |

`FL-100` is a deterministic flight and `EXP-8000` a seeded pending expense.
Bookings are created on demand; use returned IDs, not a presumed `BOOK-100`.
The agent gets business data from MCP, not from fabricated model output.

## Start

Use the [four-service deployment](../deploy/README.md) as the main entry point:

```bash
# From the repository root; fill deploy/.env before starting.
cp deploy/.env.example deploy/.env
# Set GEMINI_MODEL=gemini-3.1-flash-lite and all required secrets.
cd deploy
docker compose -f docker-compose.yaml up -d
# Local browser: http://localhost:18085
```

Both files under `deploy/` currently use published images. The agent container
listens on **8083**, mapped to host **18085**. Nginx provides the public example
`https://agent.agile-dev.de`. Use `SESSION_COOKIE_SECURE=false` for direct local
HTTP and `true` behind HTTPS. Register the matching callback
`/login/oauth2/code/corporate` in the Corporate client.

For a standalone Maven run, provide configuration as process/IDE environment
variables first; Spring Boot does not automatically load a dotenv file:

```bash
# From the repository root, with the required credentials already configured.
mvn -f travel-agent/pom.xml test
SESSION_COOKIE_SECURE=false mvn -f travel-agent/pom.xml spring-boot:run
# Browser: http://localhost:8083 (unless PORT is overridden)
```

The module-local `travel-agent/compose.yaml` builds locally but still maps
`8085:8080`. It matches the current application only if `PORT=8080` is also
set in `travel-agent/.env`; otherwise use the deployment configuration above.
The Dockerfile's `EXPOSE 8080` does not set Spring Boot's listening port.

Cloud Run is an optional deployment target, not part of the VM Compose setup.
It requires matching container port, environment/secrets, ingress/IAM, HTTPS
callback, and a session strategy. The existing Cloud Run helper is a legacy
script; see its limitations in the [scripts guide](../scripts/README.md).

## OAuth Flow

1. Alice signs in through the Corporate realm. Spring Security stores the
   authorized client and its tokens in the server-side session.
2. **Delegated:** Token Exchange in Corporate turns Token1 into an assertion
   (Token2) for the Travel domain. JWT Bearer Grant in Travel redeems it for
   resource-specific Token3. This runs separately for Travel and Expense.
3. **Overprivileged:** Client Credentials in Travel issues a service-account
   token with both audiences and all demo scopes. The request has no `scope`
   parameter; configure the necessary Default client scopes in Keycloak.
4. Tokens are cached separately per resource/session with a 45-second expiry
   margin. Delegated cache reuse also depends on the Corporate token fingerprint.
5. Each chat request creates two MCP toolsets with request-local Bearer headers
   and `X-Correlation-ID`; there is no shared global bearer token.

The MCP servers always validate JWTs and scopes. `TRAVEL_AUDIENCE` and
`EXPENSE_AUDIENCE` configure server validation; they are not automatically
added to grant requests. The JWT Bearer Grant sends `scope`, not `audience`.
Keycloak audience mappers must issue the matching `aud` values.

Token1 is refreshed through the OAuth client manager when possible. MCP tokens
are renewed before agent execution as needed, not during an in-flight MCP call.
Mode changes clear both service and delegated token caches.

## Demo Walkthrough

The [live test guide](../deploy/README.md#test-real-allowdeny-decisions) provides
prompts, expected events, prerequisites, and reset commands.

- **Overprivileged:** All required scopes, including `expenses.approve`, are
  present. `approve_expense(EXP-8000)` passes authorization.
- **Delegated:** The Expense token has `expenses.create expenses.read` and no
  `expenses.approve`. Approval produces `DENY`; reads remain allowed.
- **Google Agent Identity:** Execution is refused until a real integration exists.

For a repeatable comparison, test Delegated while `EXP-8000` is still `PENDING`,
then Overprivileged. An `ALLOW` decision does not guarantee business success;
an already approved expense cannot be approved again.

## Inspector

Configure `INSPECTOR_URL` and `INSPECTOR_API_KEY`. Deployment Compose sets the
internal endpoint to `http://inspector:8090` with the same key for all senders.
The Inspector receives metadata, not full JWTs or secrets, and is not the
policy decision point.

The agent sends token lifecycle events, mode/resource-specific token-claim
snapshots, and `AGENT_ERROR`. `TOKEN1_ISSUED` indicates a Corporate token is
available in Delegated mode or a service credential was issued in Overprivileged
mode; it is not proof of a new browser login.

The MCP servers already send real `TOOL_AUTHORIZATION` events with decision
`ALLOW` or `DENY`. They do not use `TOOL_ALLOWED`/`TOOL_DENIED` as separate
phases, and the agent does not infer decisions from LLM text.

## Verification and Remaining Limits

Tests cover session-mode isolation, browser security configuration, token
response/error handling, safe claim decoding, sanitized MCP diagnostics, and
Inspector claim-event delivery. These do not replace live Keycloak/MCP/model
integration checks. The configured environment has been exercised manually;
Cloud Run and Google Agent Identity are not verified deployment paths here.

```bash
mvn -f travel-agent/pom.xml test
```

For deployment verification, check browser login/callback/CSRF, refresh,
Corporate-to-Travel trust/grants, actual token audiences/scopes, MCP `tools/list`
and `tools/call`, and the server's `TOOL_AUTHORIZATION` events. A tool denial can
be represented as an MCP error; do not require the browser chat endpoint itself
to return HTTP 403. Inspect the server decision and the business result.

## Documentation

- [Google ADK for Java](https://github.com/google/adk-java)
- [Keycloak cross-domain chaining](https://www.keycloak.org/securing-apps/oauth-identity-authorization-chaining-across-domains)
- [Keycloak JWT Authorization Grant](https://www.keycloak.org/securing-apps/jwt-authorization-grant)

## MCP Error Diagnostics

Errors during MCP initialization and tool-list loading are logged compactly
per server (`travel-mcp` or `expense-mcp`) with a correlation ID and reported
to the Inspector as `AGENT_ERROR`. Diagnostics include the endpoint without
URL credentials, query, or fragment, along with sanitized cause messages,
such as `HTTP 401 Unauthorized`, connection errors, or protocol errors.
HTTP status codes are shown only if the underlying library provides them
in its error message.

Example (abbreviated):

```text
Agent request failed correlationId=... mode=DELEGATED phase=AGENT_RUN error=MCP server=expense-mcp endpoint=https://expense.agile-dev.de/mcp operation=initialize/list_tools failed: ... HTTP 401 Unauthorized
```

Tokens and configured secrets are masked. Unfiltered retry logs from
`com.google.adk.tools.mcp.McpToolset` are disabled; the sanitized message
appears after the SDK retries. A tool-loading error is not yet a `DENY`
decision for an individual tool. The browser error response remains general;
details are available in server logs and the Inspector.

## Inspect Delegated MCP Token Claims

After the JWT Bearer Grant and when reusing a delegated token,
`DelegatedTokenProvider` logs only its actual `iss`, `aud`, and `scope` claims.
Each message includes `correlationId`, `resource=TRAVEL` or `resource=EXPENSE`,
and `source=issued` or `source=cache`. The values come from the JWT payload,
not from the requested scopes or the separate fields of the token endpoint
response. Other claims, the full token, and secrets are not output.
Unreadable tokens produce `claims=unavailable` without interrupting the
agent request.

```bash
cd deploy
docker compose -f docker-compose.yaml logs --since=5m travel-agent | rg 'Delegated token claims'
```

Example:

```text
Delegated token claims correlationId=... resource=EXPENSE source=issued signatureVerified=false iss="https://keycloak.agile-dev.de/realms/travel" aud=["expense-mcp"] scope="expenses.create expenses.read"
```

This is diagnostic decoding only, not signature verification; that is why
`signatureVerified=false` appears in the message. Authoritative validation
of signature, issuer, expiration, and audience remains with the MCP servers.
The audience must contain the respective `TRAVEL_AUDIENCE`/`EXPENSE_AUDIENCE`
value. For the Delegated DENY test, `expenses.approve` must not be present
in the Expense token.

The same sanitized claim values are also sent to the Inspector:
`DELEGATED_TOKEN_CLAIMS_TRAVEL` and `DELEGATED_TOKEN_CLAIMS_EXPENSE` show
issuer (`iss`), audiences (`aud`), and scopes (`scope`) directly in the timeline.
The correlation ID connects them to the chat request. The reason states
`source=issued` or `source=cache` and `signatureVerified=false`.
The fields contain only decoded token claims, not requested target values.
Unreadable tokens produce `claims=unavailable`; missing claims are displayed
as `<missing>`. This diagnostic is an INFO message, not an ALLOW/DENY decision.

For this display, the agent and Inspector images must be rebuilt and updated.
The existing event fields `issuer`, `audience`, and `scopes` are reused;
the API does not require a new event schema.

In Overprivileged mode, actual service-account token claims are also sent
after issuance and when using the cache. The phases
`OVERPRIVILEGED_TOKEN_CLAIMS_TRAVEL` and `OVERPRIVILEGED_TOKEN_CLAIMS_EXPENSE`
show the same claim fields as the Delegated events. The reason additionally
contains `mode=OVERPRIVILEGED` or `mode=DELEGATED`, resource, and source.
In Overprivileged mode, Human is labeled `service-account`.
Audiences and scopes are taken unchanged from the tokens, not filtered for
the respective resource: a token with both MCP audiences therefore shows both
values. This allows comparison of `expenses.approve` in particular with the
restricted delegated Expense token.
