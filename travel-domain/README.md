# Travel Domain — Spring AI MCP Server

Java 25 / Maven / Spring Boot **4.1.2** / Spring AI **2.0.1** /
Streamable HTTP / Spring Security OAuth2 JWT Resource Server.

## MCP Tools

| Tool | Required scope |
|---|---|
| `search_flights(origin, destination, date)` | `travel.read` |
| `book_flight(flightId, traveler, date)` | `travel.book` |
| `get_booking(bookingId)` | `travel.read` |

Travel provides deterministic STR → BER flights, including `FL-100` for
EUR 149. Bookings are simulated and stored in memory. They disappear on
restart. Booking IDs are assigned when booking; `BOOK-100` is not a seeded
booking. Use the returned ID for `get_booking`.

## Run and Deploy

From the repository root, with JDK 25 and Maven:

```bash
KEYCLOAK_ISSUER=https://keycloak.agile-dev.de/realms/travel \
JWT_AUDIENCE=travel-mcp \
mvn -f travel-domain/pom.xml spring-boot:run
```

The application defaults to port **8081** (`PORT` overrides it):
`http://localhost:8081/mcp` and `http://localhost:8081/actuator/health`.

For Docker, use the current four-service configuration rather than the
module's older Compose example:

```bash
# Configure deploy/.env as described in the deployment guide first.
cd deploy
docker compose -f docker-compose.yaml up -d
```

It maps `127.0.0.1:18081` to container port `8081`; the HTTPS example is
`https://travel.agile-dev.de/mcp`. Both deployment Compose files currently
use published images. The module-local `compose.yaml` still maps to container
port `8080` without setting `PORT`, so it does not match the current default.
See the [deployment guide](../deploy/README.md) for builds, TLS, and configuration.

## Authorization Modes and Keycloak

The MCP server has **no scope-bypass mode**. `DEMO_SECURITY_MODE` is still
present in older Compose examples but is not read by the current authorization
code. Both agent modes enforce the same JWT and tool-scope checks.

- **Overprivileged:** The agent uses Client Credentials in the Travel realm.
  Its service-account token must contain `travel-mcp` and `expense-mcp` audiences
  and all required scopes, including `expenses.approve` for the Expense server.
- **Delegated:** The agent obtains a user-related Travel-realm token through
  Token Exchange and JWT Bearer Grant. Travel requires audience `travel-mcp`
  and the scopes listed above.

In both modes, `iss` must match `KEYCLOAK_ISSUER` (normally the Travel realm).
No issuer change or MCP restart is needed to switch the agent mode. The server
validates signature, issuer, expiration, and audience; `TravelAuthorization`
and `@PreAuthorize` enforce `SCOPE_travel.read` / `SCOPE_travel.book`.
The server does not perform token exchange itself.

The [working audience mapper configuration](../deploy/README.md#working-audience-mappers-for-travel-and-expense-mcp)
and [automated setup](../deploy/README.md#automatically-configure-audience-mappers-and-scope-assignments)
explain the Optional/Default client scope assignments.
Use an issuer hostname reachable from the container and consistent with `iss`.

## Inspector Events

Set `INSPECTOR_URL` and `INSPECTOR_API_KEY` to publish real
`TOOL_AUTHORIZATION` events. Deployment Compose configures these automatically
with `http://inspector:8090` and the shared key.
The method-security check reports `ALLOW` or `DENY` before business execution.
The agent passes `X-Correlation-ID` to correlate these with its token events.
JWT metadata is included; bearer tokens, secrets, and tool arguments are not.
Delivery is asynchronous and best effort. An empty URL disables publishing.

`ALLOW` confirms permission, not booking success. Requests rejected during
HTTP/JWT validation have not reached a tool and do not produce tool decisions.

## Security and Tests

There is no Google Agent Identity validation, ownership enforcement, or durable
persistence. The ADK client supplies pre-provisioned bearer tokens; generic
OAuth protected-resource metadata/discovery is not implemented here.

Tests cover domain behavior, Spring method-security ALLOW/DENY decisions,
preventing business execution after denial, and Inspector HTTP delivery.
The application-context test depends on the configured issuer discovery.
These tests do not replace an end-to-end MCP test against the deployed Keycloak.

```bash
mvn -f travel-domain/pom.xml test
```

See the [live demo test guide](../deploy/README.md#test-real-allowdeny-decisions)
for the Overprivileged/Delegated comparison.
