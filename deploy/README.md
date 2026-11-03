# Deployment: Netcup MCP + Inspector, optional local Agent

## Layout

Place `deploy/` in `travel-agent/deploy/` and keep sibling directories:

```
workspace/
  travel-agent/deploy/{compose.yaml,.env,nginx/}
  travel-domain/
  expense-domain/
  identity-inspector/
```

For other paths set absolute `TRAVEL_CONTEXT`, `EXPENSE_CONTEXT`, and `INSPECTOR_CONTEXT` in `deploy/.env`.

## Start

```bash
cd travel-agent/deploy
cp .env.example .env
# Edit .env and set a random INSPECTOR_API_KEY.
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --build
# optional agent on same host (requires ../.env with Keycloak/Gemini secrets)
docker compose --env-file .env --profile local-agent up -d --build
```

The MCP servers bind only to host loopback: Travel `127.0.0.1:18081`, Expense `127.0.0.1:18082`; Inspector `127.0.0.1:18090`. Google Cloud Run Agent connects over HTTPS to the public MCP domains.

## NGINX and TLS

1. Set DNS A/AAAA records for `travel.agile-dev.de` and `expense.agile-dev.de` to your Netcup host; permit inbound TCP 80/443.
2. Issue Let's Encrypt certificates **before** enabling these HTTPS configurations (initial HTTP-only vhost or certbot standalone with port 80 free):

```bash
sudo certbot certonly --standalone -d travel.agile-dev.de
sudo certbot certonly --standalone -d expense.agile-dev.de
```

3. Copy `nginx/travel.conf` and `nginx/expense.conf` into `/etc/nginx/sites-available/` (with unique names), symlink into `sites-enabled`, then:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

4. Verify certificate renewal and NGINX reload with a certbot deploy hook; `certbot renew --dry-run` tests renewal.

**IMPORTANT:** These vhosts expose `/mcp` only. Spring AI Streamable HTTP may use more than one path depending on server version/configuration (e.g., session-related HTTP requests). Verify actual transport endpoints and adjust proxy rules if needed. The NGINX proxy forwards Authorization and disables buffering for streaming responses.

## Inspector access

The Inspector is intentionally **not publicly exposed**. Use an SSH tunnel:

```bash
ssh -L 8090:127.0.0.1:18090 afa@YOUR_NETCUP_HOST
# Browser: http://localhost:8090
```

The `inspector-local.conf` is optional and only listens on loopback. Do not publish `/api/events`, `/api/history`, or demo-reset endpoints without authentication and access control.

## Critical prerequisites before Demo 1/2

- The previously generated MCP apps still contain `DEMO_SECURITY_MODE`. This Compose config uses `delegated` permanently. **You must update the MCP applications** so the overprivileged service-account token has all needed scopes, while Alice's delegated token lacks `expenses.approve`. No live toggling of server authorization is intended.
- Keycloak issuer, audience, scopes, token chaining and method-level security must be validated end-to-end. `docker compose up` alone does not prove tool authorization.
- The Agent project has not yet passed a verified Java 25 / Maven / ADK integration build. Google Agent Identity (Demo 3) remains fail-closed pending real credential verification.
- When deploying services across multiple Netcup hosts, split the Compose services and use host-specific NGINX configs. This file assumes one host.
- Never put API keys or access tokens in NGINX access logs, Compose YAML, or version control.
