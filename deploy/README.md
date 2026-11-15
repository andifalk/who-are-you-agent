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

`compose-local.yaml` builds the modules locally. For other layouts set
`TRAVEL_CONTEXT`, `EXPENSE_CONTEXT`, `INSPECTOR_CONTEXT` and `AGENT_CONTEXT`
in `deploy/.env` (paths are relative to `deploy/`).
`docker-compose.yaml` uses the published `andifalk/*:latest` images,
including `andifalk/travel-agent:latest`.

## Start

```bash
cd deploy
cp .env.example .env
# Fill GOOGLE_API_KEY, CORPORATE_CLIENT_SECRET, TRAVEL_GRANT_CLIENT_SECRET,
# INSECURE_CLIENT_SECRET and a random INSPECTOR_API_KEY.

# Published images:
docker compose --env-file .env -f docker-compose.yaml config --quiet
docker compose --env-file .env -f docker-compose.yaml up -d

# Alternatively, build locally:
docker compose --env-file .env -f compose-local.yaml config --quiet
docker compose --env-file .env -f compose-local.yaml up -d --build
```

Both variants use `deploy/.env`; no additional parent `.env` or profile is
needed. Required credentials are checked when Compose resolves the configuration.
Only the Travel Agent receives the Gemini key and OIDC client secrets.
The Inspector and Travel Agent share `INSPECTOR_API_KEY`.

All services bind to host loopback: Travel MCP `127.0.0.1:18081`,
Expense MCP `127.0.0.1:18082`, Inspector `127.0.0.1:18090` and
Travel Agent `127.0.0.1:18085`. Both MCP containers and the agent listen on
port 8080; the Inspector listens on 8090. The agent reaches the Inspector
at `http://inspector:8090`. MCP URLs remain configurable HTTPS endpoints
so their hostnames match the OAuth resource audiences.

The template uses `SESSION_COOKIE_SECURE=false` for direct local HTTP.
Set it to `true` behind an HTTPS reverse proxy. Register the corresponding
OIDC callback URL, e.g. `http://localhost:18085/login/oauth2/code/corporate`,
in the corporate Keycloak client.

## NGINX and TLS

1. Set DNS A/AAAA records for `travel.agile-dev.de`, `expense.agile-dev.de` and `agent.agile-dev.de` to your Netcup host; permit inbound TCP 80/443.
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

**IMPORTANT:** These vhosts expose `/mcp` only. Spring AI Streamable HTTP may use more than one path depending on server version/configuration (e.g., session-related HTTP requests). Verify actual transport endpoints and adjust proxy rules if needed. The NGINX proxy forwards Authorization and disables buffering for streaming responses.

## Inspector access

The Inspector is intentionally **not publicly exposed**. Use an SSH tunnel:

```bash
ssh -L 8090:127.0.0.1:18090 afa@YOUR_NETCUP_HOST
# Browser: http://localhost:8090
```

The `inspector-local.conf` is optional and only listens on loopback. Do not publish `/api/events`, `/api/history`, or demo-reset endpoints without authentication and access control.

## Critical prerequisites before Demo 1/2

- Both MCP servers enforce their scope checks in every mode. Configure Keycloak so the overprivileged service account has the required scopes while Alice's delegated token lacks `expenses.approve`; switching the agent mode does not disable server authorization.
- Keycloak issuer, audience, scopes, token chaining and method-level security must be validated end-to-end. `docker compose up` alone does not prove tool authorization.
- The Agent project has not yet passed a verified Java 25 / Maven / ADK integration build. Google Agent Identity (Demo 3) remains fail-closed pending real credential verification.
- When deploying services across multiple Netcup hosts, split the Compose services and use host-specific NGINX configs. This file assumes one host.
- Never put API keys or access tokens in NGINX access logs, Compose YAML, or version control.

## Docker-Images bauen und nach Docker Hub pushen

Neben `docker compose -f compose-local.yaml up --build` gibt es pro Service ein Build-Skript, das das Image baut und nach Docker Hub pusht:

```bash
docker login
DOCKERHUB_NAMESPACE=<user-oder-org> deploy/build-travel.sh      # travel-domain
DOCKERHUB_NAMESPACE=<user-oder-org> deploy/build-expenses.sh    # expense-domain
DOCKERHUB_NAMESPACE=<user-oder-org> deploy/build-inspector.sh   # identity-inspector
```

Optionen (Umgebungsvariablen, siehe `deploy/docker-build-common.sh`):

| Variable | Default | Bedeutung |
|---|---|---|
| `DOCKERHUB_NAMESPACE` | – (Pflicht) | Docker-Hub-Benutzer/-Organisation |
| `IMAGE_TAG` | Git-Short-Hash (`-dirty` bei lokalen Änderungen) | Image-Tag |
| `TAG_LATEST` | `true` | zusätzlich `:latest` taggen und pushen |
| `PUSH` | `true` | `false` = nur lokal bauen (`--load`) |
| `PLATFORMS` | `linux/amd64` | z. B. `linux/amd64,linux/arm64` (Multi-Arch nur mit Push) |
| `IMAGE_NAME` | Projektverzeichnisname | abweichender Repository-Name auf Docker Hub |

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

## Echte ALLOW-/DENY-Entscheidungen testen

Diese Anleitung prüft die tatsächlichen MCP-Autorisierungen. Die Demo-Buttons
im Inspector erzeugen dagegen simulierte Ereignisse und werden hier nicht
verwendet. `INSPECTOR_DEMO_ENABLED=false` kann gesetzt bleiben.

### Keycloak-Voraussetzungen

Die Client-IDs unten sind die Beispielwerte aus `deploy/.env.example`.
Bei abweichenden IDs müssen `.env` und Keycloak übereinstimmen.

| Realm / Client | Benötigte Konfiguration |
|---|---|
| `corporate` / `CORPORATE_CLIENT_ID` (`travel-agent-bff`) | Client Authentication und Standard Flow für den Browser-Login; passendes `CORPORATE_CLIENT_SECRET`; gültige Redirect-URI `https://agent.agile-dev.de/login/oauth2/code/corporate`. Alice muss sich anmelden können. |
| `corporate` / derselbe Client | Token-Exchange für den verwendeten Grant erlauben. Der Agent fordert `EXCHANGE_SCOPE` (Beispiel `access-travel-domain`) und `EXCHANGE_AUDIENCE` (Beispiel `https://keycloak.agile-dev.de/realms/travel`) an. Die nötigen Client-/Zielberechtigungen und Mapper müssen dazu passen. |
| `travel` / `TRAVEL_GRANT_CLIENT_ID` (`travel-agent-grant`) | Client Authentication, passendes `TRAVEL_GRANT_CLIENT_SECRET` und Unterstützung/Berechtigung für den JWT-Bearer-Grant. Vertrauen zum Corporate-Issuer und Zuordnung der Assertion zu Alice konfigurieren; Signatur, Issuer und Audience der Assertion müssen akzeptiert werden. |
| `travel` / `INSECURE_CLIENT_ID` (`travel-agent-overprivileged`) | Client Authentication und Service Accounts aktivieren; `INSECURE_CLIENT_SECRET` setzen. Das Client-Credentials-Token benötigt alle unten genannten Scopes einschließlich `expenses.approve`. |

Die installierte Keycloak-Version muss sowohl den verwendeten Token-Exchange
als auch den JWT-Bearer-Grant unterstützen und die erforderlichen Features
aktiviert haben. Der Overprivileged-Modus verwendet Client Credentials ohne
Token-Exchange; Delegated verwendet Corporate-Login → Token-Exchange →
JWT-Bearer-Grant im Travel-Realm.

Für die MCP-Tokens müssen die **tatsächlich ausgestellten Claims** stimmen:

| Token | Erforderliche Claims |
|---|---|
| Overprivileged Service-Account-Token | `iss` entspricht `TRAVEL_ISSUER`; `aud` enthält **beide** Werte `TRAVEL_AUDIENCE` (`travel-mcp`) und `EXPENSE_AUDIENCE` (`expense-mcp`); `scope` enthält `travel.read travel.book expenses.create expenses.read expenses.approve`. |
| Delegated Travel-Token | Travel-Issuer, Audience `travel-mcp`, Scopes `travel.read travel.book`; Subject identifiziert Alice. |
| Delegated Expense-Token | Travel-Issuer, Audience `expense-mcp`, Scopes `expenses.create expenses.read`; **kein** `expenses.approve`; Subject identifiziert Alice. |

Der Overprivileged-Provider fordert beim Client-Credentials-Grant keinen
`scope`-Parameter an. Daher müssen die benötigten Scope-Claims und beide
Audiences über die Default-Client-Scopes/Mapper des Service-Account-Clients
im Token landen. Bei rollenabhängigen Mappern benötigt der Service Account
auch die entsprechenden Rollenzuordnungen.

Im Delegated-Modus werden `TRAVEL_SCOPES` und `EXPENSE_SCOPES` getrennt
angefordert. Audience-Mapper müssen die passenden `aud`-Werte ausstellen;
der JWT-Bearer-Grant sendet keinen `audience`-Parameter. Eine Keycloak-Rolle
allein reicht für die MCP-Prüfung nicht: Die Server prüfen Spring-Security-
Authorities `SCOPE_<scope>`, die aus dem Token-Scope abgeleitet werden.
Insbesondere darf `expenses.approve` nicht versehentlich durch einen
Default-Scope/Mapper in Alices Expense-Token gelangen.

### Vorbereitung

1. Aktualisierte Images für Agent, beide MCP-Server und Inspector starten.
   Alle Sender verwenden denselben `INSPECTOR_API_KEY`; in Compose ist
   `INSPECTOR_URL=http://inspector:8090` bereits gesetzt.
2. Agent und Inspector nebeneinander öffnen. Im Inspector muss
   `Live · SSE connected` erscheinen. Zum Anzeigen ist keine API-Key-Eingabe
   im Browser nötig. „Clear“ ist optional und benötigt den Inspector-Key;
   es löscht nur die Ereignishistorie, keine fachlichen Demo-Daten.
3. Als Alice im Agent anmelden. Zuerst **Delegated**, danach
   **Overprivileged** testen, damit `EXP-8000` beim DENY-Test noch `PENDING` ist.

### Delegated: Lesen erlaubt, Genehmigen verweigert

Im Agent **Delegated** auswählen. Diese Anfragen einzeln senden:

1. „Suche mit search_flights Flüge von STR nach BER am 2026-12-01.“
2. „Rufe get_expense für EXP-8000 auf.“
3. „Genehmige jetzt ausdrücklich die Ausgabe EXP-8000 mit approve_expense.“
4. „Rufe get_expense für EXP-8000 auf und zeige den Status.“

| Tool | Inspector-Entscheidung | Erwarteter Grund / Ergebnis |
|---|---|---|
| `search_flights` | `ALLOW` | `travel.read` vorhanden. |
| `get_expense` | `ALLOW` | `expenses.read` vorhanden; `EXP-8000` ist zunächst `PENDING`. |
| `approve_expense` | **`DENY`** | `Missing scope: expenses.approve`; die Genehmigungslogik darf nicht ausgeführt werden. |
| `get_expense` (Kontrolle) | `ALLOW` | Status bleibt `PENDING`. |

### Overprivileged: Genehmigen erlaubt

Im Agent auf **Overprivileged** wechseln und dieselben vier Anfragen senden.
Der Moduswechsel verwirft die Token-Caches der Browser-Session. Die
MCP-Scope-Prüfungen bleiben aktiv.

| Tool | Inspector-Entscheidung | Erwarteter Grund / Ergebnis |
|---|---|---|
| `search_flights` | `ALLOW` | `travel.read` vorhanden. |
| `get_expense` | `ALLOW` | `expenses.read` vorhanden. |
| `approve_expense` | **`ALLOW`** | `expenses.approve` vorhanden. |
| `get_expense` (Kontrolle) | `ALLOW` | Nach erfolgreicher Genehmigung ist der Status `APPROVED`. |

`EXP-8000` ist ein vorbereiteter Demo-Datensatz über 8000 EUR; es findet keine
reale Genehmigung oder Auszahlung statt. Eine erneute Genehmigung eines bereits
`APPROVED`-Datensatzes scheitert fachlich, kann aber weiterhin ein korrektes
`ALLOW` erzeugen, weil die Berechtigung vorhanden ist.

Die weiteren Tools benötigen in beiden Modi folgende Scopes:

| Tool | Scope | Erwartete Entscheidung bei obiger Konfiguration |
|---|---|---|
| `book_flight` | `travel.book` | `ALLOW` |
| `get_booking` | `travel.read` | `ALLOW` |
| `create_expense` | `expenses.create` | `ALLOW` |

Beim Buchen/Anlegen die tatsächlich zurückgegebenen IDs für weitere Aufrufe
verwenden; insbesondere ist `BOOK-100` kein vorab angelegter Buchungsdatensatz.

### Ereignisse bewerten und Fehler eingrenzen

Im Inspector die Phase **`TOOL_AUTHORIZATION`**, Tool, Entscheidung, Reason,
Scopes und Correlation-ID prüfen. Ein Chat-Aufruf kann mehrere Tool-Ereignisse
mit derselben ID erzeugen. Im Delegated-Modus gehört der Subject zu Alice,
im Overprivileged-Modus zum Service Account. Die Anzeige „Human“ zeigt den
JWT-Subject (gegebenenfalls eine interne Benutzer-ID); „Agent“ zeigt `azp`,
sofern der Claim vorhanden ist.

`ALLOW` bestätigt die Autorisierung, nicht den erfolgreichen fachlichen
Abschluss. Das Status-Ergebnis von `get_expense` dient als zusätzliche Kontrolle.
Eine reine Textantwort des Modells beweist keinen Tool-Aufruf; fehlen Ereignisse,
einen expliziten Tool-Aufruf verlangen und Logs prüfen. Der Runner hat keine
persistente Chat-Historie, daher IDs und benötigte Parameter in jeder Anfrage
angeben. Das Modell kann Aufrufe auslassen oder wiederholen; die Tabellen
beschreiben die Entscheidung pro tatsächlich ausgeführter Scope-Prüfung.

| Beobachtung | Nächste Prüfung |
|---|---|
| Delegated `approve_expense` ergibt `ALLOW` | Ausgestellte Token-Scopes sowie Default-/Optional-Client-Scopes und Mapper prüfen; `expenses.approve` darf nicht enthalten sein. |
| Overprivileged `approve_expense` ergibt `DENY` | `expenses.approve` im Service-Account-Token und Client-Scope-/Rollenzuordnung prüfen. |
| Keine Tool-Ereignisse | Agent-Tokenfehler, tatsächlichen Tool-Aufruf und Meldungen `Inspector rejected event` / `Inspector delivery failed` in MCP-Logs prüfen. |
| MCP lehnt bereits JWT/HTTP-Anfrage ab | Issuer, Signatur, Ablauf und Audience prüfen. Vor Tool-Dispatch gibt es noch keine Tool-Autorisierungsentscheidung. |
| Ereignisse in `/api/history`, aber nicht im Browser | Nginx-Routen, SSE-Verbindung und Proxy-Buffering prüfen. |

Auf dem Compose-Host:

```bash
cd deploy
docker compose -f docker-compose.yaml logs --since=5m travel-agent travel-mcp expense-mcp
curl -sS http://127.0.0.1:18090/api/history
```

Nach Änderungen an Keycloak ab- und erneut anmelden oder den Agent-Modus
wechseln, damit keine alten gecachten MCP-Tokens weiterverwendet werden.
Nach Änderungen an `.env` die betroffenen Container neu erstellen; ein
reiner Neustart übernimmt keine geänderten Umgebungsvariablen.

### Demo-Daten zurücksetzen

Für einen neuen Durchlauf den Expense-MCP-Container neu starten:

```bash
cd deploy
docker compose -f docker-compose.yaml restart expense-mcp
```

Dadurch werden die flüchtigen Expense-Daten verworfen und `EXP-8000` wieder
mit `PENDING` angelegt. Das löscht auch neu erzeugte Demo-Ausgaben. Inspector-
Historie und Travel-Buchungen werden dadurch nicht zurückgesetzt. Bei Verwendung
der anderen Compose-Datei in den Befehlen `-f compose-local.yaml` einsetzen.
