# Expense Domain — Spring AI MCP Server

Java 25 / Maven / Spring Boot **4.1.2** / Spring AI **2.0.1** /
Streamable HTTP / Spring Security OAuth2 JWT Resource Server.

## MCP Tools and Demo Data

| Tool | Required scope | Description |
|---|---|---|
| `create_expense(bookingId, amount)` | `expenses.create` | Creates a pending EUR expense owned by the JWT subject |
| `get_expense(expenseId)` | `expenses.read` | Reads expense details |
| `approve_expense(expenseId)` | `expenses.approve` | Approves a pending expense |

`EXP-8000` is preloaded with EUR 8,000 and status `PENDING`. Newly created IDs
start at `EXP-101`. All data is in memory and resets on restart. Approving an
already approved record fails at the business-operation level, even if the
scope check returned `ALLOW`. No real payments or approvals take place.

## Run and Deploy

From the repository root, with JDK 25 and Maven:

```bash
KEYCLOAK_ISSUER=https://keycloak.agile-dev.de/realms/travel \
JWT_AUDIENCE=expense-mcp \
mvn -f expense-domain/pom.xml spring-boot:run
```

The default application port is **8082** (`PORT` overrides it):
`http://localhost:8082/mcp` and `http://localhost:8082/actuator/health`.

For Docker, configure `deploy/.env` and use the four-service deployment:

```bash
cd deploy
docker compose -f docker-compose.yaml up -d
```

It maps `127.0.0.1:18082` to container port `8082`; the public example is
`https://expense.agile-dev.de/mcp`. Both deployment Compose files currently
use published images. The older module-local `compose.yaml` maps to container
port `8080` without setting `PORT`, which does not match the current default.
See the [deployment guide](../deploy/README.md).

## Authorization and Keycloak

`ExpenseAuthorization` requires an authenticated `JwtAuthenticationToken`
and the tool-specific `SCOPE_<scope>` authority. There is **no insecure scope
bypass**: `DEMO_SECURITY_MODE` in legacy Compose examples is not read by the
current code. Both agent modes use the same server-side checks.

| Mode | Required Expense token |
|---|---|
| Delegated | Travel-realm issuer; audience contains `expense-mcp`; `expenses.create expenses.read`; no `expenses.approve` |
| Overprivileged | Same Travel-realm issuer; audiences contain both MCP targets; all demo scopes including `expenses.approve` |

The issuer must match `KEYCLOAK_ISSUER`, and the audience must include
`JWT_AUDIENCE` (default `expense-mcp`). Signature and expiration are also
validated. The server does not silently trust a second issuer.
Switching the agent mode does not change this server configuration.

Configure audience mappers and scope assignments using the
[Keycloak guide](../deploy/README.md#working-audience-mappers-for-travel-and-expense-mcp)
or the [setup script](../deploy/README.md#automatically-configure-audience-mappers-and-scope-assignments).
The model's refusal or approval text is not the authorization boundary.

## Inspector Integration

`INSPECTOR_URL` and `INSPECTOR_API_KEY` enable real `TOOL_AUTHORIZATION`
events. Deployment Compose sets `http://inspector:8090` and a shared key.
The authorization service publishes `ALLOW`/`DENY` before invoking the business
service. Events contain tool name, reason, correlation ID, and JWT identity
metadata, but no tokens, secrets, or tool arguments. Failed delivery is logged
without changing the access decision; an empty URL disables publishing.

## Tests and Operational Limits

The current tests cover approval ALLOW/DENY, preventing business execution
on denial, and Inspector HTTP delivery. The application-context test depends
on issuer discovery. Full MCP/Keycloak behavior must also be checked using
the [demo test guide](../deploy/README.md#test-real-allowdeny-decisions).

```bash
mvn -f expense-domain/pom.xml test
curl -i http://localhost:8082/actuator/health
curl -i -X POST http://localhost:8082/mcp -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# An unauthenticated MCP request must be rejected (401).
```

Authorization reads Spring Security's `SecurityContextHolder`; the deployed
MCP transport must propagate authentication to tool invocation threads.
There is no expense ownership enforcement, approval segregation of duties,
Google Agent Identity/SPIFFE verification, or durable persistence.
This is a demo, not production expense software.

## References

- [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [Project architecture](../README.md#architecture)
