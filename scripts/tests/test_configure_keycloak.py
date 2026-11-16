import copy
import importlib.util
from pathlib import Path
import sys
import io
import os
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("configure_keycloak", Path(__file__).resolve().parents[1] / "configure-keycloak.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class FakeApi:
    def __init__(self):
        self.token = "test-admin-token"
        self.clients = [{"id": "delegated-id", "clientId": "delegated", "protocol": "openid-connect"},
                        {"id": "over-id", "clientId": "overprivileged", "protocol": "openid-connect"}]
        self.scopes = {}
        self.mappers = {}
        self.assignments = {(client["id"], kind): set() for client in self.clients for kind in ("default", "optional")}
        self.writes = []

    def call(self, method, path, body=None):
        parts = path.split("/admin/realms/travel/", 1)[1].split("/")
        if method != "GET":
            self.writes.append((method, path, copy.deepcopy(body)))
        if parts[0].startswith("clients?"):
            return copy.deepcopy(self.clients)
        if parts[0] == "client-scopes":
            if len(parts) == 1:
                if method == "GET":
                    return copy.deepcopy(list(self.scopes.values()))
                scope = {**copy.deepcopy(body), "id": "scope-" + str(len(self.scopes))}
                self.scopes[scope["id"]] = scope
                return None
            scope_id = parts[1]
            if len(parts) == 2:
                if method == "GET":
                    return copy.deepcopy(self.scopes[scope_id])
                self.scopes[scope_id] = copy.deepcopy(body)
                return None
            mappers = self.mappers.setdefault(scope_id, [])
            if method == "GET":
                return copy.deepcopy(mappers)
            if method == "POST":
                mappers.append({**copy.deepcopy(body), "id": "mapper-" + str(len(mappers))})
            else:
                index = next(i for i, item in enumerate(mappers) if item["id"] == parts[-1])
                mappers[index] = copy.deepcopy(body)
            return None
        client_id, kind = parts[1], parts[2].split("-")[0]
        assigned = self.assignments[(client_id, kind)]
        if method == "GET":
            return [copy.deepcopy(self.scopes[item]) for item in assigned]
        if method == "PUT":
            assigned.add(parts[-1])
        else:
            assigned.remove(parts[-1])


def plan(api):
    return module.ScopePlan(api, "travel", "delegated", "overprivileged", "travel-mcp", "expense-mcp")


class ConfigurationTests(unittest.TestCase):
    def setUp(self):
        output = patch("sys.stdout", new_callable=io.StringIO)
        self.output = output.start()
        self.addCleanup(output.stop)

    def test_cli_defaults_to_preview_and_does_not_evaluate_env_as_shell(self):
        api = FakeApi()
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            marker = Path(directory) / "must-not-exist"
            env_file.write_text("TRAVEL_ISSUER=http://localhost:8080/realms/travel\n"
                                "TRAVEL_GRANT_CLIENT_ID=delegated\nINSECURE_CLIENT_ID=overprivileged\n"
                                f"IGNORED=$(touch {marker})\n")
            with patch.dict(os.environ, {"KEYCLOAK_ADMIN_TOKEN": "secret-token"}, clear=True), \
                    patch.object(module, "AdminApi", return_value=api):
                module.main(["--env-file", str(env_file)])
            self.assertFalse(marker.exists())
            self.assertEqual([], api.writes)
            self.assertIn("Vorschau", self.output.getvalue())
            self.assertNotIn("secret-token", self.output.getvalue())

    def test_missing_explicit_configuration_fails_before_authentication(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(module, "AdminApi") as api:
            with self.assertRaises(module.ConfigurationError):
                module.main(["--env-file", str(Path(directory) / "missing")])
            api.assert_not_called()

    def test_preview_is_read_only_apply_creates_correct_scopes_and_is_idempotent(self):
        api = FakeApi()
        preview = plan(api)
        self.assertTrue(preview.actions)
        self.assertEqual([], api.writes)
        preview.apply()
        names = {scope["name"]: scope_id for scope_id, scope in api.scopes.items()}
        self.assertEqual(set(module.SCOPES), set(names))
        self.assertEqual(set(names.values()), api.assignments[("over-id", "default")])
        self.assertEqual({scope_id for name, scope_id in names.items() if name != "expenses.approve"},
                         api.assignments[("delegated-id", "optional")])
        self.assertFalse(api.assignments[("delegated-id", "default")])
        self.assertFalse(api.assignments[("over-id", "optional")])
        for scope_name, audience in (("travel.read", "travel-mcp"), ("expenses.read", "expense-mcp")):
            mapper = api.mappers[names[scope_name]][0]
            self.assertEqual("oidc-audience-mapper", mapper["protocolMapper"])
            self.assertEqual(audience, mapper["config"]["included.custom.audience"])
            self.assertEqual("true", mapper["config"]["access.token.claim"])
            self.assertEqual("false", mapper["config"]["id.token.claim"])
        self.assertEqual([], plan(api).actions)

    def test_corrects_existing_assignments_and_preserves_unmanaged_configuration(self):
        api = FakeApi()
        plan(api).apply()
        ids = {item["name"]: item["id"] for item in api.scopes.values()}
        api.assignments[("delegated-id", "default")].update((ids["expenses.approve"], ids["travel.read"]))
        api.assignments[("delegated-id", "optional")].add(ids["expenses.approve"])
        api.assignments[("over-id", "optional")].add(ids["travel.read"])
        api.scopes["unrelated"] = {"id": "unrelated", "name": "unrelated", "protocol": "openid-connect"}
        api.assignments[("delegated-id", "default")].add("unrelated")
        travel = api.scopes[ids["travel.read"]]
        travel["attributes"]["include.in.token.scope"] = "false"
        travel["attributes"]["custom"] = "keep-me"
        mapper = api.mappers[ids["travel.read"]][0]
        mapper["config"]["included.client.audience"] = "wrong-client"
        mapper["config"]["custom"] = "keep-me"
        other_mapper = {"id": "custom-mapper", "name": "custom-mapper", "config": {"custom": "preserve"}}
        api.mappers[ids["travel.read"]].append(copy.deepcopy(other_mapper))
        plan(api).apply()
        self.assertEqual({"unrelated"}, api.assignments[("delegated-id", "default")])
        self.assertNotIn(ids["expenses.approve"], api.assignments[("delegated-id", "optional")])
        self.assertEqual("keep-me", api.scopes[ids["travel.read"]]["attributes"]["custom"])
        self.assertEqual("", api.mappers[ids["travel.read"]][0]["config"]["included.client.audience"])
        self.assertEqual("keep-me", api.mappers[ids["travel.read"]][0]["config"]["custom"])
        self.assertIn(other_mapper, api.mappers[ids["travel.read"]])
        self.assertEqual([], plan(api).actions)

    def test_missing_client_or_conflicting_mapper_never_writes(self):
        api = FakeApi()
        api.clients.pop()
        with self.assertRaises(module.ConfigurationError):
            plan(api)
        self.assertEqual([], api.writes)
        api = FakeApi()
        plan(api).apply()
        next(iter(api.mappers.values()))[0]["protocolMapper"] = "other-type"
        api.writes.clear()
        with self.assertRaises(module.ConfigurationError):
            plan(api)
        self.assertEqual([], api.writes)

    def test_http_error_never_prints_response_body_or_token(self):
        api = module.AdminApi("https://keycloak.example", "secret-admin-token")
        api.opener = Mock()
        api.opener.open.side_effect = HTTPError("https://keycloak.example", 403, "secret-message", {}, None)
        with self.assertRaises(module.ConfigurationError) as result:
            api.call("GET", "/admin/realms/travel/clients")
        self.assertIn("HTTP 403", str(result.exception))
        self.assertNotIn("secret", str(result.exception))


if __name__ == "__main__":
    unittest.main()
