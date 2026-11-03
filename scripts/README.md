# Skripte für Travel Agent

Die Skripte werden aus dem Projekt-Root oder direkt über `scripts/...` gestartet.

| Skript | Zweck |
|---|---|
| `preflight.sh` | `.env`, Keycloak OIDC Discovery, HTTPS-MCP-Erreichbarkeit prüfen |
| `start-demo.sh overprivileged\|delegated` | Docker Compose starten; Modus in Browser-UI wählen |
| `smoke-test.sh` | Health und Login-Schutz des BFF prüfen |
| `reset-demo.sh --confirm` | **Nur** Agent-Session zurücksetzen; kein Daten-Reset der MCP-Server |
| `deploy-cloud-run.sh` | Cloud Build + Cloud Run Deployment mit expliziter Bestätigung |

## Wichtig

- Der Modus wird **pro eingeloggter Browser-Session** über die UI gewechselt, nicht über eine Container-Umgebungsvariable.
- `start-demo.sh` startet ausschließlich die `travel-agent`-Compose-App. Keycloak und MCP-Server müssen bereits erreichbar sein.
- Für Demo 3 ist die Google Agent Identity Integration noch offen. Der Start wird daher ausdrücklich verweigert.
- Die `preflight`- und `smoke-test`-Skripte ersetzen **keine** JWT-/MCP-Integrationstests.
- `reset-demo.sh` setzt **nicht** die Fixtures `FL-100`, `BOOK-100`, `EXP-8000` zurück. Dafür fehlen im bestehenden Backend sichere Reset-Schnittstellen.
- `deploy-cloud-run.sh` veröffentlicht einen privaten Cloud-Run-Service; ein Browser-BFF braucht zusätzlich eine bewusst konfigurierte Zugriffsstrategie und korrekte OAuth-Redirects.
