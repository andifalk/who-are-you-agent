# Identity Inspector — DevFest Travel Booking & Expenses

Java 25 / Spring Boot 4.1.0 / Maven / Spring MVC SSE / static HTML and JavaScript.

## Quick start

```bash
cp .env.example .env
# Edit .env and choose a long random INSPECTOR_API_KEY
# Requires Docker with JDK 25 capable base images
docker compose up --build
```

Open http://localhost:8090. Enter the API key in the browser to use the demonstration buttons. They create **illustrative** events; the Inspector does not fabricate a real verified OAuth decision.

For local Maven use JDK 25 and `export INSPECTOR_API_KEY=...` then `mvn spring-boot:run`.

## Integrate Travel/Expense MCP

POST a **sanitized, already evaluated** security event to `http://identity-inspector:8090/api/events` (inside Docker Compose) with `X-Inspector-Key`. The API key is required for write endpoints.

```bash
curl -i -X POST http://localhost:8090/api/events \
  -H "Content-Type: application/json" \
  -H "X-Inspector-Key: $INSPECTOR_API_KEY" \
  -d '{"correlationId":"req-42","phase":"delegated","tool":"approve_expense","decision":"DENY","human":"alice","agent":"travel-agent","issuer":"https://keycloak.agile-dev.de/realms/travel","audience":"expense-mcp","scopes":"expenses.create expenses.read","reason":"Missing expenses.approve scope"}'
```

Endpoints:
- `GET /` browser UI
- `GET /api/events` SSE stream (`inspector-event` messages)
- `GET /api/history` last 200 events
- `POST /api/events` authenticated ingest
- `POST /api/demo/{insecure|delegated|agent-identity}` illustrative demo timeline
- `POST /api/clear` clear event history
- `GET /actuator/health` health

## Security & operational limits

- **Bind localhost only** as provided by Compose. No login protection for read endpoints; do not expose this to the public Internet without proper authentication and authorization (reverse proxy, VPN, or SSO).
- The browser never stores the API key persistently; it is held in a password input. Do not reuse an actual Keycloak secret.
- The Inspector **does not verify JWTs** or make access-control decisions. Security enforcement remains at MCP resource servers; the Inspector receives sanitized audit events from trusted callers.
- Demo 3's SPIFFE ID is **illustrative**, not proof of Google Agent Identity. Real claims must originate from verified tokens/certificates at your MCP gateway.
- History is in memory and is lost on restart. The demo endpoint is enabled by default; set `INSPECTOR_DEMO_ENABLED=false` when deploying beyond a local lab.
- SSE stream has no heartbeat; reconnect is handled by EventSource. For long idle reverse-proxy connections, configure suitable proxy timeouts or add a heartbeat.
- For production, use mTLS/service authentication, audit persistence, request schema constraints and TLS.

## Build status

The source project was generated here, but the environment does not have JDK 25/Maven to compile it. Run `mvn clean test package` with JDK 25 before using in the talk.
