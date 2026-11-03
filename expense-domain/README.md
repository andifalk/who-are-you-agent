# Expense Domain — Spring AI MCP Server

Java **25**, Maven, **Spring Boot 4.1.0**, **Spring AI 2.0.2**, Spring Security OAuth2 Resource Server, Streamable HTTP MCP.

## Tools

| Tool | Scope in delegated mode | Description |
|---|---|---|
| `create_expense(bookingId, amount)` | `expenses.create` | Creates a pending EUR expense owned by JWT subject |
| `get_expense(expenseId)` | `expenses.read` | Reads expense details |
| `approve_expense(expenseId)` | `expenses.approve` | Approves pending expense |

Seeded expense: `EXP-8000`, amount EUR 8,000, status PENDING. In insecure mode, **any valid JWT with the right issuer and audience** can approve it; in delegated mode the additional `expenses.approve` scope is required. The `insecure` setting is for a controlled, isolated demonstration only.

## Build

Requires JDK 25 and Maven 3.9+:

```sh
mvn clean test
mvn spring-boot:run
```

Set the issuer URL to the Keycloak realm actually issuing the token, e.g.:

```sh
export KEYCLOAK_ISSUER=https://keycloak.agile-dev.de/realms/travel
export JWT_AUDIENCE=expense-mcp
export DEMO_SECURITY_MODE=delegated
mvn spring-boot:run
```

For Docker:

```sh
cp .env.example .env
# Adjust KEYCLOAK_ISSUER in .env
# Note: Keycloak must be reachable from the container and issuer claim must match.
docker compose up --build
```

Local Docker endpoint: `http://localhost:8082/mcp`; standalone Maven endpoint: `http://localhost:8080/mcp`. Cloud target: `https://expense.agile-dev.de/mcp` behind TLS reverse proxy.

## Keycloak token requirements

- `iss` equals `KEYCLOAK_ISSUER` (for Demo 2: realm `travel`).
- `aud` includes `expense-mcp` (configure an audience mapper in Keycloak).
- `scope` includes `expenses.create`, `expenses.read` and optionally `expenses.approve`.
- Demo 1: use a valid overprivileged service-account token **issued by the same configured realm**, or change issuer configuration consistently. This project does **not** silently trust a second issuer.
- `DEMO_SECURITY_MODE=insecure` disables tool-level scope checks, **not** JWT signature/issuer/audience validation.

## Security and demo caveats

This is an educational sample, not production-grade expense software. The in-memory data resets on restart; `EXP-8000` is always preloaded. The implementation does not enforce expense ownership or approval segregation of duties, and no Google Agent Identity / SPIFFE validation is included yet. Add those controls before production use.

**MCP execution context warning:** tool authorization currently reads Spring Security's `SecurityContextHolder`. Depending on MCP transport and execution threading, authentication may not be propagated to tool invocations. Before a live demo, write an end-to-end MCP `tools/call` integration test with a real JWT and verify that authorized calls succeed and unauthorized calls fail. If context propagation fails, use a per-request transport-context extractor and explicitly verify the bearer token in the tool authorization service. Never fall back to trusting a user-supplied identity field.

## Smoke checks

```sh
curl -i http://localhost:8082/actuator/health
curl -i -X POST http://localhost:8082/mcp -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# The unauthenticated MCP request must be rejected (401).
```

## Versions and references

- https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
- https://spring.io/blog/2026/06/10/spring-boot-4/
- https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/

## Build verification

This project was generated without a local JDK 25 or Maven installation, so its Maven build and MCP integration have **not** been verified in the generation environment. Run `mvn clean verify` using JDK 25 before deployment.
