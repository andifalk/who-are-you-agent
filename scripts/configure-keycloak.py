#!/usr/bin/env python3
"""Configure demo MCP scopes/audiences through the Keycloak Admin API (stdlib only)."""
import argparse
import getpass
import json
import os
from pathlib import Path
import sys
from dataclasses import dataclass
from urllib import error, parse, request


SCOPES = ("travel.read", "travel.book", "expenses.create", "expenses.read", "expenses.approve")


class ConfigurationError(Exception):
    pass


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward admin credentials to a redirect target.


class AdminApi:
    def __init__(self, server, token=""):
        self.server = server.rstrip("/")
        self.token = token
        self.opener = request.build_opener(NoRedirect())

    def call(self, method, path, body=None, form=None):
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        if form is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            data = parse.urlencode(form).encode()
        try:
            req = request.Request(self.server + path, data=data, headers=headers, method=method)
            with self.opener.open(req, timeout=20) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except error.HTTPError as failure:
            # Provider bodies can echo submitted credentials. Do not print them.
            raise ConfigurationError(f"Keycloak HTTP {failure.code} bei {method}; "
                                     "Admin-Berechtigungen, Endpoint und Serverlogs prüfen") from None
        except (error.URLError, TimeoutError, OSError):
            raise ConfigurationError("Keycloak nicht erreichbar; DNS, TLS und Netzwerk prüfen") from None
        except (ValueError, UnicodeError):
            raise ConfigurationError("Keycloak lieferte eine ungültige JSON-Antwort") from None


def segment(value):
    return parse.quote(value, safe="")


def unique(items, key, value):
    matches = [item for item in items if item.get(key) == value]
    if len(matches) > 1:
        raise ConfigurationError(f"Mehrdeutige Konfiguration für {value}")
    return matches[0] if matches else None


@dataclass
class Action:
    method: str
    path: str
    body: object
    description: str
    creates_scope: str = ""


class ScopePlan:
    def __init__(self, api, realm, delegated, overprivileged, travel_audience, expense_audience):
        self.api = api
        self.base = "/admin/realms/" + segment(realm)
        self.ids = {}
        self.actions = []
        self.clients = []
        for client_id in (delegated, overprivileged):
            clients = api.call("GET", self.base + "/clients?" + parse.urlencode({"clientId": client_id}))
            client = unique(clients, "clientId", client_id)
            if client is None:
                raise ConfigurationError(f"Client {client_id} fehlt; vorhandene OIDC-Clients werden vorausgesetzt")
            if client.get("protocol", "openid-connect") != "openid-connect":
                raise ConfigurationError(f"Client {client_id} muss OpenID Connect verwenden")
            self.clients.append(client)
        if self.clients[0]["id"] == self.clients[1]["id"]:
            raise ConfigurationError("Delegated und Overprivileged müssen unterschiedliche Clients verwenden")
        existing = api.call("GET", self.base + "/client-scopes")
        for index, name in enumerate(SCOPES):
            scope = unique(existing, "name", name)
            reference = "__MCP_SCOPE_" + str(index) + "__"
            self.ids[reference] = scope["id"] if scope else None
            path = self.base + "/client-scopes/" + reference
            if scope:
                scope = api.call("GET", self.base + "/client-scopes/" + segment(scope["id"]))
                if scope.get("protocol") != "openid-connect":
                    raise ConfigurationError(f"Client-Scope {name} muss OpenID Connect verwenden")
                attributes = dict(scope.get("attributes") or {})
                if attributes.get("include.in.token.scope") != "true":
                    attributes["include.in.token.scope"] = "true"
                    self.actions.append(Action("PUT", path, {**scope, "attributes": attributes},
                                               f"Scope {name}: Include in token scope aktivieren"))
            else:
                self.actions.append(Action("POST", self.base + "/client-scopes",
                                           {"name": name, "protocol": "openid-connect",
                                            "attributes": {"include.in.token.scope": "true"}},
                                           f"Client-Scope {name} anlegen", reference))
            if name in ("travel.read", "expenses.read"):
                audience = travel_audience if name == "travel.read" else expense_audience
                mapper_name = "travel-mcp-audience" if name == "travel.read" else "expense-mcp-audience"
                mappers = api.call("GET", path.replace(reference, segment(scope["id"]))
                                   + "/protocol-mappers/models") if scope else []
                mapper = unique(mappers, "name", mapper_name)
                if mapper and (mapper.get("protocolMapper") != "oidc-audience-mapper"
                               or mapper.get("protocol") != "openid-connect"):
                    raise ConfigurationError(f"Mapper {mapper_name} existiert mit anderem Typ; manuell prüfen")
                config = dict((mapper or {}).get("config") or {})
                config.update({"included.custom.audience": audience, "included.client.audience": "",
                               "access.token.claim": "true", "id.token.claim": "false"})
                desired = {**(mapper or {}), "name": mapper_name, "protocol": "openid-connect",
                           "protocolMapper": "oidc-audience-mapper", "config": config}
                mapper_path = path + "/protocol-mappers/models"
                if mapper != desired:
                    self.actions.append(Action("PUT" if mapper else "POST",
                                               mapper_path + ("/" + segment(mapper["id"]) if mapper else ""),
                                               desired, f"Audience-Mapper {name} → {audience} konfigurieren"))
        for client, delegated_mode in zip(self.clients, (True, False)):
            prefix = self.base + "/clients/" + segment(client["id"])
            assigned = {
                kind: api.call("GET", prefix + "/" + kind + "-client-scopes")
                for kind in ("default", "optional")
            }
            for index, name in enumerate(SCOPES):
                reference = "__MCP_SCOPE_" + str(index) + "__"
                desired_kind = ("optional" if name != "expenses.approve" else None) if delegated_mode else "default"
                for kind in ("default", "optional"):
                    present = any(item["id"] == self.ids[reference] for item in assigned[kind])
                    path = prefix + "/" + kind + "-client-scopes/" + reference
                    if present and kind != desired_kind:
                        self.actions.append(Action("DELETE", path, None,
                                                   f"{client['clientId']}: {name} aus {kind} entfernen"))
                if desired_kind and not any(item["id"] == self.ids[reference] for item in assigned[desired_kind]):
                    self.actions.append(Action("PUT", prefix + "/" + desired_kind + "-client-scopes/" + reference,
                                               None, f"{client['clientId']}: {name} als {desired_kind} zuordnen"))

    def apply(self):
        for action in self.actions:
            path = action.path
            for reference, scope_id in self.ids.items():
                if reference in path:
                    if not scope_id:
                        raise ConfigurationError("Client-Scope-ID nach Erstellung nicht verfügbar")
                    path = path.replace(reference, segment(scope_id))
            self.api.call(action.method, path, action.body)
            if action.creates_scope:
                scope = unique(self.api.call("GET", self.base + "/client-scopes"), "name", action.body["name"])
                if not scope:
                    raise ConfigurationError("Angelegter Client-Scope nicht auffindbar")
                self.ids[action.creates_scope] = scope["id"]
            print("OK: " + action.description)


def read_env(path):
    if not path.exists():
        return {}
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if separator:
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[key.strip()] = value
    return values  # No shell evaluation or variable expansion.


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, help="Konfiguration (Standard: deploy/.env)")
    parser.add_argument("--server", help="Keycloak-Basis-URL, ggf. mit /auth")
    parser.add_argument("--realm")
    parser.add_argument("--delegated-client")
    parser.add_argument("--overprivileged-client")
    parser.add_argument("--travel-audience")
    parser.add_argument("--expense-audience")
    parser.add_argument("--admin-realm", default=os.getenv("KEYCLOAK_ADMIN_REALM", "master"))
    parser.add_argument("--admin-client", default=os.getenv("KEYCLOAK_ADMIN_CLIENT_ID", "admin-cli"))
    parser.add_argument("--admin-user", default=os.getenv("KEYCLOAK_ADMIN_USERNAME"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="Angezeigte Änderungen ausführen")
    mode.add_argument("--dry-run", action="store_true", help="Nur lesen und Plan anzeigen (Standard)")
    args = parser.parse_args(argv)
    env_file = args.env_file or Path(__file__).resolve().parents[1] / "deploy/.env"
    if args.env_file and not env_file.is_file():
        raise ConfigurationError("Explizit angegebene --env-file existiert nicht")
    values = {**read_env(env_file), **os.environ}
    issuer = parse.urlsplit(values.get("TRAVEL_ISSUER", "https://keycloak.agile-dev.de/realms/travel").rstrip("/"))
    prefix, separator, realm = issuer.path.rpartition("/realms/")
    if not separator:
        raise ConfigurationError("TRAVEL_ISSUER muss auf /realms/<realm> enden")
    server = (args.server or values.get("KEYCLOAK_SERVER") or parse.urlunsplit(
        (issuer.scheme, issuer.netloc, prefix, "", ""))).rstrip("/")
    url = parse.urlsplit(server)
    if (not url.hostname or url.username or url.password or url.query or url.fragment
            or (url.scheme != "https" and not (url.scheme == "http" and url.hostname in ("localhost", "127.0.0.1", "::1")))):
        raise ConfigurationError("Keycloak-URL muss HTTPS ohne URL-Zugangsdaten/Query verwenden (lokal auch HTTP)")
    target_realm = args.realm or parse.unquote(realm)
    delegated = args.delegated_client or values.get("TRAVEL_GRANT_CLIENT_ID", "travel-agent-grant")
    overprivileged = args.overprivileged_client or values.get("INSECURE_CLIENT_ID", "travel-agent-overprivileged")
    travel_audience = args.travel_audience or values.get("TRAVEL_AUDIENCE", "travel-mcp")
    expense_audience = args.expense_audience or values.get("EXPENSE_AUDIENCE", "expense-mcp")
    if not all((target_realm, delegated, overprivileged, travel_audience, expense_audience)):
        raise ConfigurationError("Realm, Client-IDs und Audiences dürfen nicht leer sein")
    api = AdminApi(server, os.getenv("KEYCLOAK_ADMIN_TOKEN", ""))
    if not api.token:
        form = {"client_id": args.admin_client}
        secret = os.getenv("KEYCLOAK_ADMIN_CLIENT_SECRET")
        if secret:
            form.update(grant_type="client_credentials", client_secret=secret)
        else:
            user = args.admin_user or input("Keycloak Admin-Benutzer: ")
            password = os.getenv("KEYCLOAK_ADMIN_PASSWORD") or getpass.getpass("Keycloak Admin-Passwort: ")
            form.update(grant_type="password", username=user, password=password)
        response = api.call("POST", "/realms/" + segment(args.admin_realm) + "/protocol/openid-connect/token", form=form)
        api.token = (response or {}).get("access_token", "")
        if not api.token:
            raise ConfigurationError("Admin-Anmeldung lieferte kein Access-Token")
    plan = ScopePlan(api, target_realm, delegated, overprivileged, travel_audience, expense_audience)
    print(f"Realm: {target_realm}; Delegated: {delegated}; Overprivileged: {overprivileged}")
    for action in plan.actions:
        print("PLAN: " + action.description)
    if not plan.actions:
        print("Keine Änderungen erforderlich.")
    elif args.apply:
        plan.apply()
        if ScopePlan(api, target_realm, delegated, overprivileged, travel_audience, expense_audience).actions:
            raise ConfigurationError("Nachprüfung zeigt noch Abweichungen; erneut mit --dry-run prüfen")
        print("Nachprüfung erfolgreich. MCP-Token-Caches durch Moduswechsel erneuern.")
    else:
        print("Vorschau: keine Konfiguration geändert. Mit --apply ausführen.")


if __name__ == "__main__":
    try:
        main()
    except (ConfigurationError, OSError, EOFError) as failure:
        print("FEHLER: " + str(failure), file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("Abgebrochen.", file=sys.stderr)
        sys.exit(130)
