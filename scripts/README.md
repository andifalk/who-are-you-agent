# Travel Agent Scripts

Run scripts from the repository root. For the current VM deployment, the
supported entry point is the Compose configuration under `deploy/`:

```bash
# Configure deploy/.env first.
cd deploy
docker compose -f docker-compose.yaml up -d
```

## Current Helpers and Legacy Assumptions

| Script | Purpose / Current limitations |
|---|---|
| `configure-keycloak.py` | Current audience-mapper and client-scope setup; reads `deploy/.env`, defaults to preview, writes with `--apply`. |
| `smoke-test.sh` | Checks health and unauthenticated session protection. Set `AGENT_BASE_URL=http://localhost:18085` for deployment Compose; its fallback is the older port 8085. |
| `test-chaining.sh` | Exercises Token Exchange and JWT Bearer Grant using a supplied Corporate access-token file; reads root `.env` or explicit environment variables. Does not perform MCP tool calls. |
| `preflight.sh` | Legacy root `.env` checks plus OIDC discovery and HTTPS reachability. It does not validate the deployment Compose configuration or prove token/tool authorization. |
| `start-demo.sh overprivileged\|delegated` | Legacy BFF startup helper; expects root `.env` and root `compose.yaml`, which are not provided by the current repository. Use deployment Compose instead. The requested mode must still be selected in the browser UI. |
| `reset-demo.sh --confirm` | Legacy agent-only restart helper using the same missing root Compose path. It is not the reset procedure for the four-service deployment. |
| `deploy-cloud-run.sh` | Legacy optional Cloud Build/Cloud Run helper requiring `CONFIRM_CLOUD_DEPLOY=YES`. It builds the repository root (no root Dockerfile) and specifies port 8080, whereas the current agent defaults to 8083. Adapt build context/port and cloud configuration before use. |

`common.sh` changes to the repository root, reads root `.env`, and its Compose
helper targets root `compose.yaml`. It does not automatically target
`travel-agent/compose.yaml` or `deploy/docker-compose.yaml`.
The Python configuration helper has its own loader and is unaffected by this.

For the current deployment, use these commands instead of legacy start/reset:

```bash
# From the repository root:
AGENT_BASE_URL=http://localhost:18085 bash scripts/smoke-test.sh

cd deploy
# Clears agent sessions, not business data:
docker compose -f docker-compose.yaml restart travel-agent
# Resets all in-memory expenses and reseeds EXP-8000 as PENDING:
docker compose -f docker-compose.yaml restart expense-mcp
```

The mode is selected per signed-in browser session, not through a container
variable. Both MCP servers always enforce JWT and scope checks. Google Agent
Identity is unimplemented and execution is refused. Preflight/smoke checks do
not replace the [live authorization tests](../deploy/README.md#test-real-allowdeny-decisions).
Cloud Run additionally needs an intentional browser access strategy, callback,
secrets, and session configuration; it is not a verified deployment path here.

## Configure Keycloak Audience Mappers

`configure-keycloak.py` creates/corrects the audience mappers and direct scope
assignments in the Travel realm. It requires existing OIDC clients and does
not configure cross-realm trust, grants, secrets, roles, or service-account
activation. Admin passwords are entered without echo. Do not put admin secrets
in the Compose `.env`.

```bash
python3 scripts/configure-keycloak.py --admin-user admin --dry-run
python3 scripts/configure-keycloak.py --admin-user admin --apply
```

See the [complete setup guide](../deploy/README.md#automatically-configure-audience-mappers-and-scope-assignments)
for permissions, environment/CLI options, idempotency, and partial-failure handling.

Local tests use a simulated Admin API, not the live Keycloak:

```bash
python3 -B -m unittest discover -s scripts/tests -p 'test_configure_keycloak.py'
```
