# Deployment: MCP servers, Inspector and Travel Agent

## Layout

Both Compose files run all four services from this repository:

```
who-are-you-agent/
  deploy/{compose-local.yaml,docker-compose.yaml,.env,nginx/}
  travel-agent/
  travel-domain/
  expense-domain/
  identity-inspector/
```

Both `compose-local.yaml` and `docker-compose.yaml` currently run the published
`andifalk/*:latest` images, including `andifalk/travel-agent:latest`. Neither
contains build definitions. The `*_CONTEXT` variables in `.env.example` are
legacy entries and have no effect in these files. Build images using the
module Dockerfiles or `deploy/build-*.sh` before updating containers.

## Start

```bash
cd deploy
cp .env.example .env
# Fill GOOGLE_API_KEY, CORPORATE_CLIENT_SECRET, TRAVEL_GRANT_CLIENT_SECRET,
# INSECURE_CLIENT_SECRET and a random INSPECTOR_API_KEY.
# Explicitly set GEMINI_MODEL=gemini-3.1-flash-lite for the confirmed demo.

# Published images:
docker compose --env-file .env -f docker-compose.yaml config --quiet
docker compose --env-file .env -f docker-compose.yaml up -d

# Alternatively, use the other published-image Compose file:
docker compose --env-file .env -f compose-local.yaml config --quiet
docker compose --env-file .env -f compose-local.yaml up -d
```

Both variants use `deploy/.env`; no additional parent `.env` or profile is
needed. Required credentials are checked when Compose resolves the configuration.
Only the Travel Agent receives the Gemini key and OIDC client secrets.
The Inspector, Travel Agent, and both MCP servers share `INSPECTOR_API_KEY`.

All services bind to host loopback: Travel MCP `127.0.0.1:18081`,
Expense MCP `127.0.0.1:18082`, Inspector `127.0.0.1:18090` and
Travel Agent `127.0.0.1:18085`. Container ports are Travel MCP **8081**, Expense MCP **8082**, Travel Agent
**8083**, and Inspector **8090**. The agent reaches the Inspector
at `http://inspector:8090`. MCP URLs remain configurable HTTPS endpoints
for reverse-proxy access. Their hostnames are not the token audiences:
JWT `aud` must contain the configured `travel-mcp` / `expense-mcp` values.

The browser login registration currently requests `openid profile email access-travel`.
The subsequent exchange separately uses `EXCHANGE_SCOPE`, whose template/fallback
is `access-travel-domain`. Configure the actual Keycloak scopes accordingly;
changing this environment variable does not change the login registration.
The app/template model fallback is still `gemini-2.5-flash`; explicitly set
`GEMINI_MODEL=gemini-3.1-flash-lite` for the confirmed demo configuration.

The template uses `SESSION_COOKIE_SECURE=false` for direct local HTTP.
Set it to `true` behind an HTTPS reverse proxy. Register the corresponding
OIDC callback URL, e.g. `http://localhost:18085/login/oauth2/code/corporate`,
in the corporate Keycloak client.

## NGINX and TLS

1. Set DNS A/AAAA records for `travel.agile-dev.de`, `expense.agile-dev.de` and `agent.agile-dev.de` to your web hosting provider’s host; permit inbound TCP 80/443.
2. Issue Let's Encrypt certificates **before** enabling these HTTPS configurations (initial HTTP-only vhost or certbot standalone with port 80 free):

```bash
sudo certbot certonly --standalone -d travel.agile-dev.de
sudo certbot certonly --standalone -d expense.agile-dev.de
sudo certbot certonly --standalone -d agent.agile-dev.de
```

3. Copy `nginx/travel.conf`, `nginx/expense.conf` and `nginx/agent.conf` into `/etc/nginx/sites-available/` (with unique names), symlink into `sites-enabled`, then:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

4. Verify certificate renewal and NGINX reload with a certbot deploy hook; `certbot renew --dry-run` tests renewal.

For the public agent, set `SESSION_COOKIE_SECURE=true` in `deploy/.env` and
recreate the agent container. Register
`https://agent.agile-dev.de/login/oauth2/code/corporate` as a valid redirect URI
for the corporate Keycloak client. `nginx/agent.conf` proxies the UI, API and
OIDC routes to `127.0.0.1:18085`; adjust the upstream if `AGENT_PORT` changes.

**IMPORTANT:** The Travel/Expense MCP vhosts expose `/mcp` only; the agent vhost forwards all application paths. Spring AI Streamable HTTP may use more than one path depending on server version/configuration (e.g., session-related HTTP requests). Verify actual transport endpoints and adjust proxy rules if needed. The NGINX proxy forwards Authorization and disables buffering for streaming responses.

## Inspector access

The Inspector container binds to loopback. Use an SSH tunnel, or explicitly
enable `nginx/inspector.conf` for an HTTPS endpoint protected by Basic
Authentication (create the password file and certificate first):

```bash
ssh -L 8090:127.0.0.1:18090 afa@YOUR_WEB_HOST
# Browser: http://localhost:8090
```

The `inspector-local.conf` is optional and only listens on loopback. Do not publish `/api/events`, `/api/history`, or demo-reset endpoints without authentication and access control.

## Critical prerequisites before Demo 1/2

- Both MCP servers enforce their scope checks in every mode. Configure Keycloak so the overprivileged service account has the required scopes while Alice's delegated token lacks `expenses.approve`; switching the agent mode does not disable server authorization.
- Keycloak issuer, audience, scopes, token chaining and method-level security must be validated end-to-end. `docker compose up` alone does not prove tool authorization.
- The agent uses ADK 1.14.0, all modules use Spring Boot 4.1.2, and the MCP modules use Spring AI 2.0.1. Automated tests cover authorization and diagnostics; the configured demos have been exercised manually. Google Agent Identity (Demo 3) remains unimplemented and fail-closed.
- When deploying services across multiple hosts at your web hosting provider, split the Compose services and use host-specific NGINX configs. This file assumes one host.
- Never put API keys or access tokens in NGINX access logs, Compose YAML, or version control.

## Build Docker Images and Push to Docker Hub

Run the build scripts from the repository root. They build the module image
and push it to Docker Hub; `PUSH=false` builds and loads locally instead.
Neither deployment Compose file builds images:

```bash
docker login
DOCKERHUB_NAMESPACE=<user-or-org> deploy/build-agent.sh       # travel-agent
DOCKERHUB_NAMESPACE=<user-or-org> deploy/build-travel.sh      # travel-domain
DOCKERHUB_NAMESPACE=<user-or-org> deploy/build-expenses.sh    # expense-domain
DOCKERHUB_NAMESPACE=<user-or-org> deploy/build-inspector.sh   # identity-inspector
```

For example, build the agent locally with the image name used by Compose:

```bash
# From the repository root; uses the existing Docker build setup.
DOCKERHUB_NAMESPACE=andifalk PUSH=false deploy/build-agent.sh
cd deploy
docker compose -f docker-compose.yaml up -d --force-recreate --no-deps travel-agent
```

When publishing updated images, pull them on the deployment host before
recreating the affected services. A different namespace or image name must
also be reflected in the Compose service's `image` setting.

Options (environment variables, see `deploy/docker-build-common.sh`):

| Variable | Default | Meaning |
|---|---|---|
| `DOCKERHUB_NAMESPACE` | – (required) | Docker Hub user/organization |
| `IMAGE_TAG` | Short Git hash (`-dirty` for local changes) | Image tag |
| `TAG_LATEST` | `true` | Additionally tag and push `:latest` |
| `PUSH` | `true` | `false` = build locally only (`--load`) |
| `PLATFORMS` | `linux/amd64` | e.g. `linux/amd64,linux/arm64` (multi-arch only with push) |
| `IMAGE_NAME` | Project directory name | Alternative repository name on Docker Hub |

## Live MCP authorization events

Both MCP services send `TOOL_AUTHORIZATION` events to the internal Inspector
at `http://inspector:8090`, using the shared `INSPECTOR_API_KEY`. The agent
passes its correlation ID in `X-Correlation-ID` so token events and tool
access decisions can be matched. Other callers receive a generated ID if
no valid correlation header is supplied; the header is for tracing only.

`ALLOW` means the required scope passed the server authorization check;
it does not assert that the subsequent business operation succeeded.
`DENY` means the scope/authentication check rejected execution. Travel's
method-security check publishes before the tool method; Expense publishes
before invoking its business service. HTTP requests rejected by JWT validation
before MCP tool dispatch have no tool identity and do not generate these events.

The payload contains tool name, decision, required-scope reason and validated
JWT identity metadata (subject, issuer, audience, scopes and OAuth client `azp`).
The subject displayed under Human may also identify a service account.
Tokens, client secrets and tool arguments are never included. Publishing is
asynchronous and best effort; delivery failures are logged and do not change
access decisions. The Inspector must therefore not be treated as a durable
audit log. Outside Compose, configure `INSPECTOR_URL` and `INSPECTOR_API_KEY`
on each MCP server; an empty URL disables publishing.

After building and publishing the updated MCP and agent images, pull and
recreate `travel-mcp`, `expense-mcp` and `travel-agent`. No Inspector demo
mode is required for live authorization events.

## Test Real ALLOW/DENY Decisions

This guide checks actual MCP authorization. The demo buttons in the Inspector,
by contrast, generate simulated events and are not used here.
`INSPECTOR_DEMO_ENABLED=false` can remain set.

### Keycloak Prerequisites

The client IDs below are the example values from `deploy/.env.example`.
If different IDs are used, `.env` and Keycloak must match.

| Realm / Client | Required configuration |
|---|---|
| `corporate` / `CORPORATE_CLIENT_ID` (`travel-agent-bff`) | Client Authentication and Standard Flow for browser login; matching `CORPORATE_CLIENT_SECRET`; valid redirect URI `https://agent.agile-dev.de/login/oauth2/code/corporate`. Alice must be able to sign in. |
| `corporate` / same client | Allow Token Exchange for the grant used. The agent requests `EXCHANGE_SCOPE` (example `access-travel-domain`) and `EXCHANGE_AUDIENCE` (example `https://keycloak.agile-dev.de/realms/travel`). The required client/target permissions and mappers must match. |
| `travel` / `TRAVEL_GRANT_CLIENT_ID` (`travel-agent-grant`) | Client Authentication, matching `TRAVEL_GRANT_CLIENT_SECRET`, and support/permission for the JWT Bearer Grant. Configure trust in the Corporate issuer and mapping of the assertion to Alice; the assertion's signature, issuer, and audience must be accepted. |
| `travel` / `INSECURE_CLIENT_ID` (`travel-agent-overprivileged`) | Enable Client Authentication and Service Accounts; set `INSECURE_CLIENT_SECRET`. The Client Credentials token requires all scopes listed below, including `expenses.approve`. |

The installed Keycloak version must support both the Token Exchange used here
and the JWT Bearer Grant, with the required features enabled.
Overprivileged mode uses Client Credentials without Token Exchange; Delegated
uses Corporate login → Token Exchange → JWT Bearer Grant in the Travel realm.

For the MCP tokens, the **actually issued claims** must be correct:

| Token | Required claims |
|---|---|
| Overprivileged service-account token | `iss` matches `TRAVEL_ISSUER`; `aud` contains **both** values `TRAVEL_AUDIENCE` (`travel-mcp`) and `EXPENSE_AUDIENCE` (`expense-mcp`); `scope` contains `travel.read travel.book expenses.create expenses.read expenses.approve`. |
| Delegated Travel token | Travel issuer, audience `travel-mcp`, scopes `travel.read travel.book`; subject identifies Alice. |
| Delegated Expense token | Travel issuer, audience `expense-mcp`, scopes `expenses.create expenses.read`; **no** `expenses.approve`; subject identifies Alice. |

The Overprivileged provider does not request a `scope` parameter in the
Client Credentials grant. Therefore, the required scope claims and both
audiences must end up in the token through the service-account client's
default client scopes/mappers. For role-dependent mappers, the service account
also needs the corresponding role assignments.

In Delegated mode, `TRAVEL_SCOPES` and `EXPENSE_SCOPES` are requested separately.
Audience mappers must issue the appropriate `aud` values; the JWT Bearer Grant
does not send an `audience` parameter. A Keycloak role alone is not sufficient
for the MCP check: the servers check Spring Security authorities
`SCOPE_<scope>`, derived from the token scope. In particular, `expenses.approve`
must not accidentally enter Alice's Expense token through a default scope/mapper.

### Working Audience Mappers for Travel and Expense MCP

The configuration confirmed in the demo deployment adds two explicit audience
mappers in realm **`travel`**. Without them, the delegated tokens contained
the correct scopes but only `aud="account"`. As a result, MCP initialization
already failed the JWT audience check while loading tools.

1. Select realm **`travel`** in the Keycloak Admin UI.
2. Open **Client scopes → `travel.read` → Mappers → Configure a new mapper → Audience**
   and create the Travel mapper from the table.
3. Similarly, create the Expense mapper under
   **Client scopes → `expenses.read` → Mappers**.

| Setting | Travel mapper | Expense mapper |
|---|---|---|
| Name | `travel-mcp-audience` | `expense-mcp-audience` |
| Mapper Type | `Audience` | `Audience` |
| Included Custom Audience | `travel-mcp` | `expense-mcp` |
| Included Client Audience | empty | empty |
| Add to access token | **On** | **On** |
| Add to ID token | Off | Off |

The audience values must exactly match the configured `TRAVEL_AUDIENCE` and
`EXPENSE_AUDIENCE` values. Alternatively, **Included Client Audience** can point
to an existing Keycloak client with exactly that ID; leave the custom audience
field empty in that case. The explicit mapper adds the audience independently
of a role-dependent Audience Resolve mapper. An additional `account` entry is
allowed and does not need to be removed.

The client scopes must also have **Include in token scope = On** so that the
scope names already in use are included under `scope` in the access token.
Audience mappers add `aud`; they grant neither tool permissions nor
`expenses.approve` automatically.

**Delegated:** Under **Clients → client from `TRAVEL_GRANT_CLIENT_ID` →
Client scopes**, assign the scopes as **Optional**:

```text
travel.read
travel.book
expenses.create
expenses.read
```

The agent requests `scope=travel.read travel.book` for Travel and
`scope=expenses.create expenses.read` for Expense. This activates the mapper
of the respective requested read scope. Do not assign `expenses.approve`
as a default scope to this demo client; it is not needed for the restricted
demo. The final token claims must yield:

```json
{"aud": ["travel-mcp"], "scope": "travel.read travel.book"}
{"aud": ["expense-mcp"], "scope": "expenses.create expenses.read"}
```

**Overprivileged:** Under **Clients → client from `INSECURE_CLIENT_ID` →
Client scopes**, assign the same four scopes plus `expenses.approve` as
**Default**. Client Authentication and Service Accounts must be enabled.
The agent does not send a `scope` parameter here, so assignment as Optional
is not sufficient. Both audience mappers must take effect during issuance.
For role-dependent scope/claim mappers, add the corresponding service-account
roles.

```json
{
  "aud": ["travel-mcp", "expense-mcp"],
  "scope": "travel.read travel.book expenses.create expenses.read expenses.approve"
}
```

The JSON examples show the relevant target values: scope order is irrelevant;
additional standard scopes such as `profile`/`email` and the audience `account`
are possible. A single audience may also be represented as a string instead
of an array. In both modes, `iss` must match the Travel realm. The Corporate
exchange scope (e.g. `access-travel`) and its realm audience belong to the
preceding exchange step; they do not replace the MCP audiences in the final
Travel-realm tokens.

**Verify changes:** Already issued tokens are not changed by a new mapper.
Switch the agent mode and switch back to clear the MCP token caches.
After changes to login/Corporate scopes, also sign out and sign in again.
A container rebuild is not required for changes limited to Keycloak mappers.

In the Inspector, check the following events with the current correlation ID:

| Mode / Event | Audience contains | Scopes contain |
|---|---|---|
| `DELEGATED_TOKEN_CLAIMS_TRAVEL` | `travel-mcp` | `travel.read travel.book` |
| `DELEGATED_TOKEN_CLAIMS_EXPENSE` | `expense-mcp` | `expenses.create expenses.read`, **no** `expenses.approve` |
| `OVERPRIVILEGED_TOKEN_CLAIMS_TRAVEL` | Both MCP audiences | All five demo scopes, including `expenses.approve` |
| `OVERPRIVILEGED_TOKEN_CLAIMS_EXPENSE` | Both MCP audiences | All five demo scopes, including `expenses.approve` |

The claim events are decoding diagnostics (`signatureVerified=false`);
authoritative JWT validation still takes place in the MCP servers. Verify
the actually active mode using the event phase or `GET /api/session`.
Then run the tool tests below: `search_flights` must return `ALLOW` in both
modes, while `approve_expense` must do so only in Overprivileged mode.

### Automatically Configure Audience Mappers and Scope Assignments

From the repository directory, the configuration documented above can be
set up using Python 3 (without additional packages):

```bash
# Read and display changes only; the admin password is entered without echo.
python3 scripts/configure-keycloak.py --admin-user admin --dry-run

# Apply configuration, then check it against the desired state again.
python3 scripts/configure-keycloak.py --admin-user admin --apply
```

By default, the script reads `deploy/.env` without executing the file as shell
code. Environment variables override file entries, and CLI arguments override
the values derived from them. It uses `TRAVEL_ISSUER`,
`TRAVEL_GRANT_CLIENT_ID`, `INSECURE_CLIENT_ID`, `TRAVEL_AUDIENCE`, and
`EXPENSE_AUDIENCE`; without a file, the example values apply.
Specify different paths or IDs explicitly:

```bash
python3 scripts/configure-keycloak.py \
  --env-file deploy/.env \
  --server https://keycloak.agile-dev.de \
  --realm travel \
  --delegated-client travel-agent-grant \
  --overprivileged-client travel-agent-overprivileged \
  --travel-audience travel-mcp \
  --expense-audience expense-mcp \
  --admin-user admin --apply
```

Both OIDC clients and the Travel realm must already exist.
The script creates missing client scopes, enables **Include in token scope**,
creates or corrects the named audience mappers, and sets the direct client
scope assignments:

| Client | Result |
|---|---|
| Delegated | `travel.read`, `travel.book`, `expenses.create`, `expenses.read` as **Optional**; direct Default/Optional assignments of `expenses.approve` are removed. |
| Overprivileged | All five scopes, including `expenses.approve`, as **Default**; corresponding Optional assignments are removed. |

A second run makes no changes if the state is unchanged. Unrelated mappers,
other attributes, and assignments of other scopes are preserved.
Changes to shared scope definitions/named mappers also affect other clients
using those scopes. A mapper with the same name but a different mapper type
causes an abort before applying changes. The full planning stage first reads
the configuration of both clients; `--dry-run` does not change realm/client
configuration (admin login may request a token).

The admin identity requires read permissions for clients/client scopes and
corresponding write permissions for `--apply`. Supported authentication methods:

- Interactive: `--admin-user`, password entered without echo in the console;
  defaults to `admin-cli` in admin realm `master`. The admin client must allow
  the Password Grant. `--admin-realm` and `--admin-client` can be customized.
- Existing admin access token: provide `KEYCLOAK_ADMIN_TOKEN` through a
  protected environment variable; it is not printed.
- Admin service account: set `KEYCLOAK_ADMIN_CLIENT_ID`,
  `KEYCLOAK_ADMIN_CLIENT_SECRET`, and, if needed, `KEYCLOAK_ADMIN_REALM`.
  The script then uses Client Credentials.

`KEYCLOAK_ADMIN_USERNAME` and `KEYCLOAK_ADMIN_PASSWORD` are also available
as options. Admin secrets do not belong in `deploy/.env` (that file is loaded
into the agent container) and are not accepted as CLI arguments. The script
does not log admin tokens, passwords, or unfiltered HTTP error responses.
It does not follow HTTP redirects and verifies TLS certificates; HTTP is
allowed only for loopback addresses.

The run configures **no** Corporate clients, Token Exchange/JWT Grant
permissions, trust relationships, client secrets, service-account activation,
or role assignments. Likewise, unrelated mappers are not inspected for
indirectly granted permissions. These prerequisites and the actually issued
claims must still be checked according to the test guide.

Admin API changes are not transactional: if an error occurs, preceding steps
may already have been saved. Fix the error, check with `--dry-run`, and apply
again. Then switch the agent mode and switch back so that new MCP tokens
are requested with the mappers.

The endpoints used are documented in the
[Keycloak Admin REST API](https://www.keycloak.org/docs-api/latest/rest-api/index.html).
The local tests use only a simulated API:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_configure_keycloak.py'
```

### Preparation

1. Start updated images for the agent, both MCP servers, and the Inspector.
   All senders use the same `INSPECTOR_API_KEY`; Compose already sets
   `INSPECTOR_URL=http://inspector:8090`.
2. Open the agent and Inspector side by side. The Inspector must show
   `Live · SSE connected`. No API key entry in the browser is needed to view
   events. “Clear” is optional and requires the Inspector key; it deletes
   only the event history, not business demo data.
3. Sign in to the agent as Alice. Test **Delegated** first, then
   **Overprivileged**, so that `EXP-8000` is still `PENDING` during the DENY test.

### Delegated: Reading Allowed, Approval Denied

Select **Delegated** in the agent. Send these requests individually:

1. “Use search_flights to search for flights from STR to BER on 2026-12-01.”
2. “Call get_expense for EXP-8000.”
3. “Now explicitly approve expense EXP-8000 using approve_expense.”
4. “Call get_expense for EXP-8000 and show the status.”

| Tool | Inspector decision | Expected reason / Result |
|---|---|---|
| `search_flights` | `ALLOW` | `travel.read` present. |
| `get_expense` | `ALLOW` | `expenses.read` present; `EXP-8000` is initially `PENDING`. |
| `approve_expense` | **`DENY`** | `Missing scope: expenses.approve`; the approval logic must not be executed. |
| `get_expense` (verification) | `ALLOW` | Status remains `PENDING`. |

### Overprivileged: Approval Allowed

Switch the agent to **Overprivileged** and send the same four requests.
Changing the mode clears the browser session's token caches. MCP scope
checks remain active.

| Tool | Inspector decision | Expected reason / Result |
|---|---|---|
| `search_flights` | `ALLOW` | `travel.read` present. |
| `get_expense` | `ALLOW` | `expenses.read` present. |
| `approve_expense` | **`ALLOW`** | `expenses.approve` present. |
| `get_expense` (verification) | `ALLOW` | After successful approval, the status is `APPROVED`. |

`EXP-8000` is a pre-seeded demo record for EUR 8,000; no real approval or
payout takes place. Attempting to approve an already `APPROVED` record again
fails at the business-operation level, but can still produce a correct
`ALLOW` because the permission is present.

The other tools require the following scopes in both modes:

| Tool | Scope | Expected decision with the configuration above |
|---|---|---|
| `book_flight` | `travel.book` | `ALLOW` |
| `get_booking` | `travel.read` | `ALLOW` |
| `create_expense` | `expenses.create` | `ALLOW` |

When booking/creating, use the IDs actually returned for subsequent calls;
in particular, `BOOK-100` is not a pre-seeded booking record.

### Evaluate Events and Troubleshoot

Before the tool decisions, `DELEGATED_TOKEN_CLAIMS_TRAVEL` and
`DELEGATED_TOKEN_CLAIMS_EXPENSE` show the actually decoded `iss`, `aud`, and
`scope` claims in the respective event card. The reason distinguishes
`source=issued` and `source=cache` and marks the data as diagnostics without
signature verification (`signatureVerified=false`). The same correlation ID
connects claims and tool calls. Neither tokens nor secrets are transmitted.
For Overprivileged, the corresponding events are
`OVERPRIVILEGED_TOKEN_CLAIMS_TRAVEL` and `OVERPRIVILEGED_TOKEN_CLAIMS_EXPENSE`.
Both modes show all audiences and scopes actually present; there is no
filtering by resource. The reason names the mode explicitly. In particular,
check that only the Overprivileged token contains `expenses.approve`.


In the Inspector, check phase **`TOOL_AUTHORIZATION`**, tool, decision, reason,
scopes, and correlation ID. One chat request can produce multiple tool events
with the same ID. In Delegated mode, the subject belongs to Alice; in
Overprivileged mode, to the service account. The “Human” display shows the
JWT subject (possibly an internal user ID); “Agent” shows `azp` if the claim
is present.

`ALLOW` confirms authorization, not successful completion of the business
operation. The status returned by `get_expense` provides an additional check.
A text-only model response does not prove a tool call; if events are missing,
request an explicit tool call and check logs. The runner has no persistent
chat history, so include IDs and required parameters in every request.
The model may omit or repeat calls; the tables describe the decision per
scope check actually executed.

| Observation | Next check |
|---|---|
| Delegated `approve_expense` returns `ALLOW` | Check issued token scopes and Default/Optional client scopes and mappers; `expenses.approve` must not be included. |
| Overprivileged `approve_expense` returns `DENY` | Check `expenses.approve` in the service-account token and client-scope/role assignments. |
| No tool events | Check agent token errors, actual tool calls, and `Inspector rejected event` / `Inspector delivery failed` messages in MCP logs. |
| MCP already rejects JWT/HTTP request | Check issuer, signature, expiration, and audience. Before tool dispatch, there is no tool authorization decision yet. |
| Events in `/api/history`, but not in the browser | Check Nginx routes, SSE connection, and proxy buffering. |

On the Compose host:

```bash
cd deploy
docker compose -f docker-compose.yaml logs --since=5m travel-agent travel-mcp expense-mcp
curl -sS http://127.0.0.1:18090/api/history
```

After Keycloak changes, sign out and sign in again or change the agent mode
so that old cached MCP tokens are not reused. After changing `.env`, recreate
the affected containers; a simple restart does not pick up changed environment
variables.

### Reset Demo Data

For another run, restart the Expense MCP container:

```bash
cd deploy
docker compose -f docker-compose.yaml restart expense-mcp
```

This discards the in-memory Expense data and recreates `EXP-8000` with
`PENDING`. It also deletes newly created demo expenses. Inspector history
and Travel bookings are not reset. When using the other Compose file,
substitute `-f compose-local.yaml` in the commands.
