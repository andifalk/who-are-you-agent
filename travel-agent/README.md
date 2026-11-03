# Travel Agent — Who Are You, Agent? (DevFest 2026)

Java 25 / Maven / Spring Boot 4.1 / Google ADK for Java 1.12.0 / Gemini /
Keycloak (corporate + travel) / two **MCP Streamable HTTP** clients.

## Wichtiger Implementierungsstatus

**Quellcode-Projekt / Integrationskandidat; kein verifiziertes Release.**
In der Erzeugungsumgebung standen nur JDK 21 und kein Maven zur Verfügung.
Daher sind Maven-Kompilation, ADK-API-Kompatibilität, Keycloak-Konfiguration,
Cloud Run und End-to-End-Sicherheitstests noch nicht verifiziert.

**Demo 3 ist absichtlich fail-closed**: Die UI bietet den Modus an, aber die
Serverlogik verweigert die Ausführung, bis eine echte, verifizierte Google
Agent Identity (nicht bloß ein Cloud Run Service-Account-ID-Token) eingebaut
und vom MCP Resource Server überprüft wird. Es wäre unsicher, hier
eine bloße Header-Behauptung als verifizierte Agentenidentität auszugeben.

**TOOL_ALLOWED/TOOL_DENIED** müssen von den MCP-Servern nach tatsächlicher
serverseitiger Entscheidung an den Inspector gesendet werden. Der Agent
veröffentlicht `TOKEN1_ISSUED`, `TOKEN_EXCHANGED`, `JWT_GRANT_REDEEMED`.
Die drei Eventnamen sind Lifecycle-Indikatoren; `TOKEN1_ISSUED` bedeutet
„Corporate Token verfügbar“ (auch wenn er aus der Session stammt).

## Warum eigene Web UI?

Die Java ADK Web UI (`com.google.adk.web.AdkWebServer`) ist laut offizieller
ADK-Dokumentation vorhanden, **aber ausdrücklich nur für Development/Debugging**
und nicht für Production. Eine bestätigte Lösung für deinen BFF-Login,
isolierte OAuth-User-Sessions und den vorgesehenen Remote-/Cloud-Run-Modus
ist damit nicht belegt. Daher: Spring MVC BFF + Session Cookie + CSRF.

## Voraussetzungen

- JDK 25 und Maven 3.9+
- Keycloak >=26.5 mit aktivierter *JWT Authorization Grant* Preview-Funktion
- Zwei Keycloak-Realms `corporate` und `travel`
- Corporate OIDC Confidential Client `travel-agent-bff` mit Standard Flow
  und **Standard Token Exchange**; Authorization Code + PKCE konfigurieren
- Travel OIDC Client `travel-agent-grant` mit JWT Authorization Grant
  und erlaubtem Corporate Identity Provider
- Travel Client `travel-agent-overprivileged` mit Client Credentials
  und `travel.read`, `travel.book`, `expenses.create`, `expenses.read`,
  `expenses.approve` (Audience-Mappings für **beide** MCP-Server)
- Im Corporate-Realm Client Scope `access-travel-domain` mit Audience
  `https://keycloak.agile-dev.de/realms/travel`, optional dem BFF zugewiesen
- Im Travel-Realm Client Scopes/Role Mappers für die gewünschten Token3
  (Travel und Expense) **getrennt** und auf Alice beschränkt.
- Beide MCP-Server müssen JWTs aus dem Travel-Realm akzeptieren und
  `aud` + Scope + Subject/Ownership prüfen. Bisher generierte
  Server mit `DEMO_SECURITY_MODE` benötigen dafür Anpassungen.

**ACHTUNG:** Die bislang generierte Travel-Domain nutzt `@ToolParam` und
die Expense-Domain `@McpToolParam`; die wirklichen MCP-Tool-Schemas sind
nach dem Start mit `tools/list` zu verifizieren. Die Agenten-Anweisung
enthält die exakten Toolnamen und erwarteten Parameter:

| MCP | Tool | Eingaben |
|---|---|---|
| travel | `search_flights` | `origin`, `destination`, `date` (ISO) |
| travel | `book_flight` | `flightId`, `traveler`, `date` (ISO) |
| travel | `get_booking` | `bookingId` |
| expense | `create_expense` | `bookingId`, `amount` (EUR) |
| expense | `get_expense` | `expenseId` |
| expense | `approve_expense` | `expenseId` |

Deterministische Fixtures: `FL-100`, `BOOK-100`, `EXP-8000`.
Der Agent selbst erzeugt keine Flugdaten, sondern fragt die MCP-Server.

## Start

```bash
cp .env.example .env
# .env mit echten Secrets befüllen (niemals committen!)
mvn -U clean verify
mvn spring-boot:run
# Browser: http://localhost:8080
```

Lokaler HTTP-Betrieb benötigt für die Cookie-Einstellung
`SESSION_COOKIE_SECURE=false`. Für HTTPS/Cloud Run **true** lassen.

Docker:
```bash
docker compose up --build -d
# http://localhost:8085
```

**Cloud Run:** `Dockerfile` verwenden, Umgebungsvariablen/Secrets aus
Secret Manager bereitstellen, OIDC Callback URI
`https://<cloud-run-domain>/login/oauth2/code/corporate` im Corporate-Realm
hinterlegen. Kein `--allow-unauthenticated` ohne vorgelagerten
Zugriffsschutz; Browser-BFF benötigt HTTP-Erreichbarkeit für autorisierte
Nutzer. Cloud Run hinter HTTPS mit korrekten Forwarded Headers.
Cloud Run ist stateless: Standard-HTTP-Session nur mit einer Instanz
und ohne langlebige Verfügbarkeitsgarantie geeignet. Für echte
Skalierung Sessions/Tokens verschlüsselt in einem zentralen Store ablegen.

## OAuth-Ablauf

1. Alice öffnet die Web UI und authentifiziert sich im Corporate-Realm
   (Authorization Code). Spring Security hält Token1 im OAuth Client.
2. Für `DELEGATED` führt `DelegatedTokenProvider` RFC 8693 aus:
   Token1 -> Corporate Realm -> JWT Assertion (Token2).
3. RFC 7523 JWT Bearer Grant: Token2 -> Travel Realm -> Token3.
4. Der Provider speichert **getrennte** Token3 je Resource und Browser-Session,
   prüft Ablaufzeit (45 s Reserve) und Token1-Änderung.
5. Pro Chat-Aufruf werden **zwei neue ADK McpToolsets** mit zwei
   Streamable-HTTP-Verbindungen erstellt. Der Bearer Header wird
   **nur in diesen request-lokalen Toolsets** gesetzt, nie global.
6. Der `InMemoryRunner` ist ebenfalls pro Aufruf neu.
   **Trade-off:** Keine persistente LLM-Konversationshistorie.
   Für den Vortrag ausreichend, für Multi-Turn-Dialoge später
   per-session Runner/History mit sicherem Lifecycle ergänzen.

**Token-Renewal:** Token1 wird über Spring OAuth2AuthorizedClientManager
aktualisiert, sofern Refresh Token verfügbar; Token3 wird vor jeder
Agent-Ausführung bei Bedarf erneuert. Bei Tokenablauf **während**
eines langen ADK-Aufrufs wird noch nicht mitten im HTTP-Transport
nachgeladen. Dann Chat erneut senden.

**Audience/Scope:** Die `TRAVEL_AUDIENCE`/`EXPENSE_AUDIENCE` dienen
der Keycloak-Mapper-Konfiguration und Resource-Server-Validierung.
Die Anwendung sendet beim JWT Grant derzeit `scope`, nicht
`audience` (nicht standardisierter JWT-Grant-Parameter).
Die tatsächliche Token3-Audience wird durch Keycloak konfiguriert
und MUSS auf MCP-Seite überprüft werden.

## Demo-Ablauf

**Demo 1 — Overprivileged Agent:**
UI auf `OVERPRIVILEGED`; Client Credentials im Travel-Realm.
Die MCP-Server validieren weiterhin JWT-Signatur/Issuer/Audience/Scopes.
`approve_expense(EXP-8000)` gelingt nur mit korrekt konfiguriertem
`expenses.approve`-Scope und geeignetem Benutzer-/Rollenmodell.

**Demo 2 — Delegated:**
UI auf `DELEGATED`; Token1/2/3; `approve_expense(EXP-8000)`
muss mit fehlendem `expenses.approve` abgewiesen werden.

**Demo 3 — Google Agent Identity:**
UI bietet `GOOGLE_AGENT_IDENTITY` an, aber diese Version **verweigert
die Ausführung**. Erst nach Integration von echten Google Agent
Identity Credentials plus verifizierter Agentenautorisierung
auf MCP-Seite aktivieren. Der Modus darf nie nur aufgrund
eines `X-Agent-Identity`-Headers freigeschaltet werden.

## Inspector

Konfiguriere `INSPECTOR_URL` und `INSPECTOR_API_KEY`.
Die App sendet ausschließlich Metadaten; keine JWTs/Secrets.
Der Inspector ist nicht die Policy Decision Point.
Für **TOOL_ALLOWED/TOOL_DENIED** bitte Spring Security
Authorization Decision Listener/Tool Interceptor auf den
MCP-Servern instrumentieren. Nicht aus LLM-Text „erraten“.

## Noch zu prüfen (Release-Gates)

1. `mvn -U clean verify` unter Java 25.
2. Browser-Login + Callback + CSRF + Session-Refresh.
3. Corporate Token Exchange + Travel JWT Grant mit echter
   Keycloak-Version und korrekten Client-/IdP-Berechtigungen.
4. Beide `tools/list` Schemas und Tool-Invocations.
5. Audience/Scope-Policy beider MCP-Server inklusive
   Negative-Tests (`approve_expense` muss 403 liefern).
6. Google ADK Streamable HTTP bei Spring AI MCP 2.0.2.
7. Cloud Run: HTTPS/Callback/Session/Secret Manager.
8. Google Agent Identity Integration + MCP-Verifikation:
   **noch nicht implementiert**.

## Dokumentation

- https://adk.dev/get-started/java/
- https://adk.dev/runtime/web-interface/
- https://adk-labs.github.io/adk-docs/api-reference/java/com/google/adk/tools/mcp/McpToolset.html
- https://www.keycloak.org/securing-apps/oauth-identity-authorization-chaining-across-domains
- https://www.keycloak.org/securing-apps/jwt-authorization-grant
