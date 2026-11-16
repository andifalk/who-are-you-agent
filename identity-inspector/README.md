# Identity Inspector — DevFest Travel Booking & Expenses

Java 25 / Maven / Spring Boot **4.1.2** / Spring MVC SSE /
static HTML and JavaScript.

The Inspector displays actual token lifecycle/claim events from the agent and
actual authorization decisions from the MCP servers. It does not validate JWTs
or make access decisions; those checks remain in the MCP resource servers.

## Run and Deploy

The main entry point is the [four-service deployment](../deploy/README.md):

```bash
# From the repository root; configure deploy/.env first.
cd deploy
docker compose -f docker-compose.yaml up -d
```

The service is named `inspector` in deployment Compose, listens on **8090**,
and is mapped to **127.0.0.1:18090**. The agent and both MCP servers use
`http://inspector:8090` internally with the same `INSPECTOR_API_KEY`.
For browser access, use an SSH tunnel or the supplied HTTPS Nginx configuration
with Basic Authentication. Read endpoints have no application-level login.

The module-local Compose file is also usable from `identity-inspector/`:

```bash
cp .env.example .env
# Set a long random INSPECTOR_API_KEY; choose INSPECTOR_DEMO_ENABLED explicitly.
docker compose up --build
# Browser: http://localhost:8090
```

It names the service `identity-inspector` and exposes loopback port 8090.
That service name applies only to the module-local Compose setup.
For Maven, provide `INSPECTOR_API_KEY` as a process/IDE environment variable
and run `mvn -f identity-inspector/pom.xml spring-boot:run` from the repository
root. Spring Boot does not automatically load a dotenv file.

## Real Events and Simulated Demo Buttons

Real events arrive automatically; no browser API-key entry is required for
viewing them. `INSPECTOR_DEMO_ENABLED=false` does not disable ingestion or SSE.
The browser key is required for **Clear** and the illustrative demo buttons.
Those buttons generate simulated events and do not call the actual agent,
Keycloak, or MCP tools, nor do they change the agent's mode.

The application and module-local Compose default demo generation to `true`.
The four-service deployment instead defaults it to `false`, configurable through
`INSPECTOR_DEMO_ENABLED` in `deploy/.env`. When disabled, `/api/demo/{mode}`
returns 404 after API-key validation. A missing/wrong write API key returns 403.

| Event phase | Meaning |
|---|---|
| `TOKEN1_ISSUED`, `TOKEN_EXCHANGED`, `JWT_GRANT_REDEEMED` | Token lifecycle metadata from the agent; cached Corporate-token availability is not a new login. |
| `DELEGATED_TOKEN_CLAIMS_TRAVEL` / `DELEGATED_TOKEN_CLAIMS_EXPENSE` | Actual decoded delegated token `iss`, `aud`, and `scope`. |
| `OVERPRIVILEGED_TOKEN_CLAIMS_TRAVEL` / `OVERPRIVILEGED_TOKEN_CLAIMS_EXPENSE` | Actual decoded service-account token claims for comparison. |
| `TOOL_AUTHORIZATION` | Real server-side tool decision, `ALLOW` or `DENY`, before business execution. |
| `AGENT_ERROR` | Sanitized token/MCP/agent error diagnostics. |

Token-claim cards show issuer, all audiences, and scopes, with mode/resource,
`source=issued` or `source=cache`, correlation ID, and `signatureVerified=false`.
Decoding is not signature validation. MCP servers independently validate tokens.
Lifecycle events without a tool/decision display their phase and neutral `INFO`.
`ALLOW` does not guarantee the business operation succeeded. HTTP/JWT failures
before tool dispatch do not produce a tool-level decision.

The Human field in MCP authorization events represents the JWT subject and
may be a service-account or internal user ID. Agent represents the JWT `azp`
when present. Agent token-claim events identify their sender as `travel-agent`
and mark the Overprivileged human as `service-account`.

## API and Event Ingestion

Send sanitized events to `POST /api/events` using `X-Inspector-Key`:

```bash
# For the four-service deployment, run on the Compose host.
curl -i -X POST http://127.0.0.1:18090/api/events \
  -H "Content-Type: application/json" \
  -H "X-Inspector-Key: $INSPECTOR_API_KEY" \
  -d '{"correlationId":"req-42","phase":"TOOL_AUTHORIZATION","tool":"approve_expense","decision":"DENY","human":"alice","agent":"travel-agent","issuer":"https://keycloak.agile-dev.de/realms/travel","audience":"expense-mcp","scopes":"expenses.create expenses.read","reason":"Missing scope: expenses.approve"}'
```

`correlationId` and `phase` are required. `tool` and `decision` are optional
for lifecycle/claim events. Claims use the existing `issuer`, `audience`,
and `scopes` fields; no full token or secret belongs in the payload.

| Endpoint | Purpose |
|---|---|
| `GET /` | Browser UI |
| `GET /api/events` | SSE stream with `ready` and `inspector-event` messages |
| `GET /api/history` | Last 200 events |
| `POST /api/events` | Event ingestion; API key required |
| `POST /api/clear` | Clear history; API key required |
| `POST /api/demo/{insecure,delegated,agent-identity}` | Illustrative events; API key and enabled demo generation required |
| `GET /actuator/health` | Health |

The demo route accepts one mode name, not the literal comma-separated list.

## Tests and Limits

The event validation tests cover lifecycle events without tool/decision while
retaining required correlation ID/phase checks. Agent and MCP-module tests
also verify actual HTTP publication against local test servers. The
application-context test requires `INSPECTOR_API_KEY` to be configured.

```bash
# From the repository root, with INSPECTOR_API_KEY set.
mvn -f identity-inspector/pom.xml test
```

The Inspector keeps 200 events in memory and loses them on restart. Senders
publish asynchronously on a best-effort basis; this is not a durable audit
log. The SSE stream has no heartbeat, and EventSource reconnects automatically.
The browser key is not persisted; never reuse a Keycloak client secret as the
Inspector key. Demo Agent Identity/SPIFFE data is illustrative, not verified.

For production use, this sample would need durable auditing and appropriate
service authentication/access controls. See the
[demo test guide](../deploy/README.md#test-real-allowdeny-decisions) and
[project architecture](../README.md#architecture).
