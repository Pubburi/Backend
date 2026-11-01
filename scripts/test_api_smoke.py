"""Guard against running mutation smoke checks against an unrelated database."""

import json
import os
from pathlib import Path
import socket
import subprocess
import unittest
from unittest.mock import Mock, patch

from api_smoke import (
    ApiClient, change_ci_product, ci_search_outbox, count_ci_favorites, count_ci_preferences, count_ci_product_features,
    favorite_contract, json_charset_contract, multipart_security_contract, private_preference_contract, product_feature_contract,
    promote_ci_fixture_admin, require_disposable_database, session_authority_contract,
    verify_feature_openapi, search_outbox_contract,
)


class DisposableDatabaseGuardTests(unittest.TestCase):
    def setUp(self):
        self.environ = {
            "PUBBURI_SMOKE_MUTATING": "1",
            "PUBBURI_DB_NAME": "pubburi_ci",
            "PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:62928/pubburi_ci",
            "PUBBURI_DB_USER": "ci_user",
            "PUBBURI_DB_PASSWORD": "ci_test_only",
            "PUBBURI_DB_ROOT_PASSWORD": "ci_root_only",
            "PUBBURI_SMOKE_CONTAINER_ID": "disposable-test-container",
            "PUBBURI_SMOKE_RUN_TOKEN": "test-run-marker-0123456789",
        }
        self.inspect = subprocess.CompletedProcess([], 0, json.dumps([{
            "NetworkSettings": {"Ports": {"3306/tcp": [{"HostIp": "127.0.0.1", "HostPort": "62928"}]}}
        }]))

    def test_unsafe_configuration_is_rejected_before_any_docker_or_database_access(self):
        cases = [
            {"PUBBURI_SMOKE_MUTATING": "0"},
            {"PUBBURI_DB_NAME": "pubburi"},
            {"PUBBURI_DB_URL": "jdbc:mysql://db.example.invalid:62928/pubburi_ci"},
            {"PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:62928/another_ci"},
            {"PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:62928/pubburi_ci?socketFactory=custom"},
            {"PUBBURI_SMOKE_RUN_TOKEN": ""},
        ]
        for overrides in cases:
            with self.subTest(overrides=list(overrides)):
                run = Mock()
                with self.assertRaises(RuntimeError):
                    require_disposable_database(dict(self.environ, **overrides), run=run)
                run.assert_not_called()

    def test_port_mismatch_never_reads_the_database_marker(self):
        run = Mock(return_value=self.inspect)
        with self.assertRaisesRegex(RuntimeError, "port"):
            require_disposable_database(dict(
                self.environ, PUBBURI_DB_URL="jdbc:mysql://127.0.0.1:3306/pubburi_ci"), run=run)
        self.assertEqual(run.call_count, 1)

    def test_price_mutation_rechecks_safety_before_any_write(self):
        with patch("api_smoke.subprocess.run") as run:
            with self.assertRaises(RuntimeError):
                change_ci_product(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"), 1, 1000, "name")
            run.assert_not_called()

    def test_search_outbox_checks_cannot_read_or_inject_failures_outside_disposable_database(self):
        unsafe = dict(self.environ, PUBBURI_SMOKE_MUTATING="0")
        with patch("api_smoke.subprocess.run") as run, patch("api_smoke.ApiClient") as client:
            with self.assertRaises(RuntimeError): search_outbox_contract(unsafe)
            with self.assertRaises(RuntimeError): ci_search_outbox(unsafe, 1, failing_trigger=True)
            run.assert_not_called(); client.assert_not_called()

    def test_search_outbox_failure_trigger_is_limited_to_a_numeric_fixture_id(self):
        with patch("api_smoke.require_disposable_database") as guard, patch("api_smoke.subprocess.run") as run:
            for invalid in (True, "1; DELETE FROM t_product", -1, 2147483648):
                with self.assertRaises(ValueError): ci_search_outbox(self.environ, invalid, failing_trigger=True)
            run.assert_not_called()
            ci_search_outbox(self.environ, 123, failing_trigger=True)
            sql = run.call_args.args[0][-1]
            self.assertIn("NEW.product_id=123", sql)
            self.assertNotIn(self.environ["PUBBURI_DB_PASSWORD"], str(run.call_args.args))
            self.assertNotIn(self.environ["PUBBURI_DB_ROOT_PASSWORD"], str(run.call_args.args))
            self.assertIn("-uroot", run.call_args.args[0])
            self.assertEqual(run.call_args.kwargs['env']['MYSQL_PWD'], self.environ['PUBBURI_DB_ROOT_PASSWORD'])
            guard.assert_called_with(self.environ)

    def test_search_outbox_ddl_requires_root_but_queries_keep_the_application_user(self):
        with patch("api_smoke.require_disposable_database"), patch("api_smoke.subprocess.run") as run:
            for operation in (True, False):
                with self.assertRaises(RuntimeError):
                    ci_search_outbox(dict(self.environ, PUBBURI_DB_ROOT_PASSWORD=""), 123, failing_trigger=operation)
            run.assert_not_called()
            run.return_value.stdout = '1\n{"productId":123}\n'
            ci_search_outbox(self.environ, 123)
            self.assertIn('-uci_user', run.call_args.args[0])
            self.assertEqual(run.call_args.kwargs['env']['MYSQL_PWD'], self.environ['PUBBURI_DB_PASSWORD'])
        with patch('api_smoke.require_disposable_database'), patch('api_smoke.ApiClient') as client:
            with self.assertRaises(RuntimeError):
                search_outbox_contract(dict(self.environ, PUBBURI_DB_ROOT_PASSWORD=''))
            client.assert_not_called()

    def test_search_outbox_drop_inspects_exact_fixture_before_root_ddl(self):
        expected = {'table':'t_product_search_outbox','timing':'BEFORE','event':'INSERT',
                    'body':'SET NEW.source_version = IF(NEW.product_id=123, 0, NEW.source_version)'}
        for mutation in ({'table':'t_product'}, {'timing':'AFTER'}, {'event':'UPDATE'},
                         {'body':'SET NEW.source_version = 0'}, {'body':None}):
            with patch('api_smoke.require_disposable_database'), patch('api_smoke.subprocess.run') as run:
                run.return_value.stdout = json.dumps(dict(expected, **mutation))
                with self.assertRaisesRegex(RuntimeError,'unowned'):
                    ci_search_outbox(self.environ,123,failing_trigger=False)
                self.assertEqual(run.call_count,1)
                self.assertIn('-uci_user',run.call_args.args[0]); self.assertNotIn('DROP TRIGGER',run.call_args.args[0][-1])
        with patch('api_smoke.require_disposable_database'), patch('api_smoke.subprocess.run') as run:
            run.return_value.stdout=json.dumps(expected)
            ci_search_outbox(self.environ,123,failing_trigger=False)
            self.assertEqual(run.call_count,2)
            self.assertIn('-uci_user',run.call_args_list[0].args[0])
            self.assertIn('-uroot',run.call_args_list[1].args[0])
            self.assertEqual(run.call_args_list[1].args[0][-1], 'DROP TRIGGER ci_search_outbox_fail_123')

    def test_price_mutation_encodes_names_and_keeps_password_out_of_arguments(self):
        name = "상품 ' ; DELETE FROM t_order; --"
        with patch("api_smoke.require_disposable_database") as guard, \
                patch("api_smoke.subprocess.run") as run:
            change_ci_product(self.environ, 1, 1000, name)
            guard.assert_called_once_with(self.environ)
            statement = run.call_args.args[0][-1]
            self.assertIn(name.encode("utf-8").hex(), statement)
            self.assertNotIn("DELETE", statement)
            self.assertNotIn(self.environ["PUBBURI_DB_PASSWORD"], str(run.call_args.args))

    def test_fixture_admin_promotion_is_guarded_and_rejects_existing_account_names(self):
        with patch("api_smoke.subprocess.run") as run:
            with self.assertRaises(RuntimeError):
                promote_ci_fixture_admin(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"), "ci_admin_" + "a" * 32)
            run.assert_not_called()
        with patch("api_smoke.require_disposable_database"), patch("api_smoke.subprocess.run") as run:
            with self.assertRaises(ValueError):
                promote_ci_fixture_admin(self.environ, "admin")
            run.assert_not_called()

    def test_session_workflow_requires_the_disposable_database_before_any_http(self):
        with patch("api_smoke.ApiClient") as client:
            with self.assertRaises(RuntimeError):
                session_authority_contract(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"))
            client.assert_not_called()

    def test_feature_workflow_requires_the_disposable_database_before_any_http(self):
        with patch("api_smoke.ApiClient") as client:
            with self.assertRaises(RuntimeError):
                product_feature_contract(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"))
            client.assert_not_called()

    def test_private_preferences_require_the_disposable_database_before_any_http(self):
        with patch("api_smoke.ApiClient") as client:
            with self.assertRaises(RuntimeError):
                private_preference_contract(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"))
            client.assert_not_called()

    def test_favorites_require_the_disposable_database_before_any_http(self):
        with patch("api_smoke.ApiClient") as client:
            with self.assertRaises(RuntimeError):
                favorite_contract(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"))
            client.assert_not_called()

    def test_multipart_probes_require_the_disposable_database_before_any_http(self):
        with patch("api_smoke.ApiClient") as client:
            with self.assertRaises(RuntimeError):
                multipart_security_contract(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"))
            client.assert_not_called()

    def test_json_charset_probes_require_the_disposable_database_before_any_http(self):
        with patch("api_smoke.ApiClient") as client:
            with self.assertRaises(RuntimeError):
                json_charset_contract(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"))
            client.assert_not_called()

    def test_favorite_counts_are_guarded_and_reject_nonfixture_accounts(self):
        with patch("api_smoke.subprocess.run") as run:
            with self.assertRaises(RuntimeError):
                count_ci_favorites(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"), "ci_favorite_" + "a" * 32)
            run.assert_not_called()
        for user_id in ("admin", "ci_favorite_' OR 1=1", None):
            with patch("api_smoke.require_disposable_database"), patch("api_smoke.subprocess.run") as run:
                with self.assertRaises(ValueError):
                    count_ci_favorites(self.environ, user_id)
                run.assert_not_called()

    def test_private_preference_counts_reject_existing_or_injected_account_ids(self):
        with patch("api_smoke.subprocess.run") as run:
            with self.assertRaises(RuntimeError):
                count_ci_preferences(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"), "ci_pref_" + "a" * 32)
            run.assert_not_called()
        for user_id in ("admin", "ci_pref_' OR 1=1", None):
            with patch("api_smoke.require_disposable_database"), patch("api_smoke.subprocess.run") as run:
                with self.assertRaises(ValueError):
                    count_ci_preferences(self.environ, user_id)
                run.assert_not_called()

    def test_feature_counts_are_guarded_and_only_accept_integer_fixture_ids(self):
        with patch("api_smoke.subprocess.run") as run:
            with self.assertRaises(RuntimeError):
                count_ci_product_features(dict(self.environ, PUBBURI_SMOKE_MUTATING="0"), 1)
            run.assert_not_called()
        for product_id in (True, 0, "1;DELETE FROM t_product", 2147483648):
            with patch("api_smoke.require_disposable_database"), patch("api_smoke.subprocess.run") as run:
                with self.assertRaises(ValueError):
                    count_ci_product_features(self.environ, product_id)
                run.assert_not_called()

    def test_openapi_contract_detects_missing_fields_and_lost_unknown_semantics(self):
        fields = {name: {"nullable": True} for name in (
            "volumeMl", "description", "tasteTags", "aromaTags", "sourceName", "sourceUrl", "sourceNote")}
        schema = {"properties": {"data": {"properties": dict(fields, productId={"type": "integer"})}}}
        document = {"paths": {
            "/api/products/{id}/features": {"get": {"responses": {"200": {"content": {
                "application/json": {"schema": schema}}}}}},
            "/api/admin/products/{id}/features": {"put": {"requestBody": {"content": {
                "application/json": {"schema": {"properties": dict(fields)}}}}}},
        }}
        verify_feature_openapi(document)
        fields["tasteTags"].clear()
        with self.assertRaisesRegex(AssertionError, "unknown/null"):
            verify_feature_openapi(document)

    def test_missing_marker_is_rejected(self):
        run = Mock(side_effect=[self.inspect, subprocess.CompletedProcess([], 0, "")])
        with self.assertRaisesRegex(RuntimeError, "marker"):
            require_disposable_database(self.environ, run=run)

    def test_matching_container_schema_and_marker_are_accepted(self):
        token = self.environ["PUBBURI_SMOKE_RUN_TOKEN"]
        run = Mock(side_effect=[self.inspect, subprocess.CompletedProcess([], 0, token + "\n")])
        require_disposable_database(self.environ, run=run)
        self.assertEqual(run.call_count, 2)
        # Passwords travel through the environment, not process arguments/logs.
        self.assertNotIn(self.environ["PUBBURI_DB_PASSWORD"], str(run.call_args.args))

    def test_api_client_rejects_non_local_port_inputs(self):
        for port in ("0", "65536", "example.invalid", "9987/api"):
            with self.subTest(port=port), self.assertRaises(ValueError):
                ApiClient(port)

    def test_occupied_port_is_rejected_without_stopping_its_listener(self):
        root = Path(__file__).resolve().parents[1]
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            result = subprocess.run(
                ["bash", str(root / "scripts/smoke.sh")], cwd=root,
                env=dict(os.environ, PUBBURI_SERVER_PORT=str(port)),
                capture_output=True, text=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("port is occupied", result.stdout + result.stderr)
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                pass


if __name__ == "__main__":
    unittest.main()
