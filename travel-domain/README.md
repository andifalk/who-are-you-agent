# Travel Domain — Spring AI MCP Server

Java 25 · Spring Boot 4 · Spring AI 2 · Streamable HTTP · OAuth2 JWT Resource Server.

## MCP tools

- `search_flights(origin, destination, date)` — fixed sample flights STR → BER, deterministic prices.
- `book_flight(flightId, traveler, date)` — simulated in-memory booking.
- `get_booking(bookingId)` — look up a booking.

No real bookings or external APIs. Restarting the app resets in-memory bookings.

## Run

```bash
mvn test
KEYCLOAK_ISSUER=https://keycloak.agile-dev.de/realms/travel \
JWT_AUDIENCE=travel-mcp \
mvn spring-boot:run
```

Endpoint: `http://localhost:8080/mcp`; health: `http://localhost:8080/actuator/health`.

Docker:

```bash
cp .env.example .env
# edit .env
 docker compose up --build -d
```

Endpoint with Compose: `http://localhost:8081/mcp`.

## Modes

- `DEMO_SECURITY_MODE=delegated` (default): JWT signature, issuer, audience, expiry and `SCOPE_travel.read` / `SCOPE_travel.book` enforced.
- `DEMO_SECURITY_MODE=insecure`: JWT still required and audience/issuer still checked, but **any authenticated caller** may invoke tools. **Intentional demo-only vulnerability**. Do not expose publicly in this mode.

To accept a corporate service-account JWT in Demo 1, set `KEYCLOAK_ISSUER` to corporate realm and issue the token with audience `travel-mcp`. In Demo 2 set issuer to travel realm. Changing modes requires restart.

## Keycloak configuration

Issue a JWT access token with `aud` containing `travel-mcp`. In delegated mode assign scopes `travel.read` and `travel.book` to the authorized principal; Keycloak must actually emit them in the `scope` claim. Spring Security maps those to `SCOPE_travel.read` and `SCOPE_travel.book`. The server does **not** perform token exchange; the ADK agent/host does so before calling MCP.

**Important:** `iss` must exactly match `KEYCLOAK_ISSUER`. When using Docker, use a DNS name reachable both by the Keycloak issuer and by the container; a container-only hostname may break issuer validation.

## Security and scope

- `@PreAuthorize` on annotated MCP tools is intended to enforce scope checks; verify invocation through Spring proxies with a real MCP integration test before presenting.
- This app has no Google Agent Identity validation yet; that is a later integration.
- No user ownership checks or durable persistence; add before production use.
- The HTTP security chain protects `/mcp` but does not yet implement OAuth protected-resource metadata/discovery for generic MCP clients. ADK client uses pre-provisioned bearer tokens.
- JWT audience is validated for both demo modes.
- Demo 1's insecurity is deliberate: it authorizes authenticated callers without checking business scopes.

## Tests

`mvn test` tests domain behavior. **A full MCP protocol and Spring Security integration test has not been run in this generated package.** Test the configured Keycloak realm and MCP transport before deployment.
