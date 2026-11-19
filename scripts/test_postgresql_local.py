"""Local PostgreSQL release ownership, provenance and secret boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch, MagicMock
import zipfile

import local_stack as local
import postgresql_local as pg
import postgresql_import as importing


class PostgreSQLLocalTests(unittest.TestCase):
    def env(self):
        return dict(PUBBURI_PG_URL='jdbc:postgresql://127.0.0.1:25432/owned_ci',
            PUBBURI_PG_DB_NAME='owned_ci', PUBBURI_PG_USER='runtime', PUBBURI_PG_PASSWORD='runtime-secret',
            PUBBURI_PG_RUN_TOKEN='owned-fixture-token', PUBBURI_PG_CA='/tmp/public-ca.pem',
            PUBBURI_PG_CONTAINER_ID='a'*64, PUBBURI_PG_VALIDATION_LABEL='owned-fixture',
            PUBBURI_JPA_SOURCE_ID='12345678-1234-1234-1234-123456789abc', PUBBURI_JPA_REHEARSAL='1',
            PUBBURI_SERVER_PORT='15001', PUBBURI_FRONTEND_PORT='15002')

    def config(self, root, env=None):
        path = root / 'config.json'
        pg.write_json(path, {'schema_version':1, 'environment':env or self.env()})
        return path

    def test_config_is_exact_private_regular_and_never_sources_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); path = self.config(root)
            self.assertEqual(pg.configuration(path), self.env())
            path.chmod(0o644)
            with self.assertRaises(RuntimeError):pg.configuration(path)
            path.chmod(0o600)
            link = root / 'link'; link.symlink_to(path)
            with self.assertRaises((RuntimeError,OSError)):pg.configuration(link)
            path.write_text('$(touch should-not-execute)')
            with self.assertRaises(RuntimeError):pg.configuration(path)
            self.assertFalse((root/'should-not-execute').exists())
            fifo=root/'fifo';os.mkfifo(fifo,0o600)
            with self.assertRaises(RuntimeError):pg.configuration(fifo)

    def test_duplicate_unknown_nonfinite_nonstring_and_shared_ports_fail(self):
        for mutation in ({'PUBBURI_PG_ADMIN_PASSWORD':'no'}, {'JAVA_TOOL_OPTIONS':'-Dsecret=no'},
                         {'SPRING_DATASOURCE_URL':'remote'}, {'PUBBURI_SERVER_PORT':True},
                         {'PUBBURI_FRONTEND_PORT':'15001'}, {'PUBBURI_SEARCH_ENDPOINT':'http://127.0.0.1:9200/private'}, {'PUBBURI_SEARCH_READ_ALIAS':'wildcard*'}, {'PUBBURI_JPA_REHEARSAL':'0'}):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(RuntimeError):pg.configuration(self.config(Path(temporary), dict(self.env(), **mutation)))
        for text in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":1} true'):
            with self.assertRaises(RuntimeError):pg.strict_json(text)

    def test_inherited_credentials_and_jvm_injection_never_reach_base(self):
        with patch.dict(os.environ, {'PATH':'/tools', 'HOME':'/user', 'PUBBURI_PG_PASSWORD':'secret',
             'PUBBURI_DB_ROOT_PASSWORD':'root', 'PGPASSWORD':'control', 'SPRING_JPA_HIBERNATE_DDL_AUTO':'create',
             'JAVA_TOOL_OPTIONS':'-javaagent:secret', 'JDK_JAVA_OPTIONS':'-Dsecret=1'}, clear=True):
            self.assertEqual(pg.base_environment(), {'PATH':'/tools', 'HOME':'/user'})

    def test_two_children_receive_only_their_owned_runtime_settings(self):
        with patch.dict(os.environ, {'PATH':'/tools','PGPASSWORD':'control','PUBBURI_DB_ROOT_PASSWORD':'root',
                'PUBBURI_CONTENT_CATALOG_PATH':'inherited-private-file'}, clear=True):
            backend, frontend = pg.child_environments(dict(self.env(),PUBBURI_CONTENT_FAMILY_BROWSE_DEMO_ENABLED='true'))
        self.assertEqual(backend['PUBBURI_PG_PASSWORD'],'runtime-secret')
        self.assertEqual(backend['PUBBURI_JPA_PORT'],'15001')
        self.assertEqual(frontend,{'PATH':'/tools','PUBBURI_FRONTEND_PORT':'15002','VITE_API_PROXY_TARGET':'http://127.0.0.1:15001'})
        for env in (backend,frontend):
            self.assertNotIn('PGPASSWORD',env);self.assertNotIn('PUBBURI_DB_ROOT_PASSWORD',env)
            self.assertNotIn('PUBBURI_CONTENT_CATALOG_PATH',env)

    def test_existing_config_ignores_retired_service_and_does_not_forward_settings(self):
        legacy = {'PUBBURI_RECOMMENDATION_PORT': '15001',
                  'PUBBURI_RECOMMENDATION_BASE_URL': 'http://127.0.0.1:18888',
                  'PUBBURI_REVIEWED_CATALOG_PATH': 'private-retired-path'}
        with tempfile.TemporaryDirectory() as temporary:
            file = self.config(Path(temporary), dict(self.env(), **legacy))
            self.assertEqual(pg.configuration(file, pg.sha(file)), self.env())
        for child in pg.child_environments(dict(self.env(), **legacy)):
            self.assertFalse(set(legacy) & child.keys())

    def test_pg_destructive_commands_reject_before_env_or_docker(self):
        for name in ('init','install','down','reset-db'):
            with self.subTest(command=name), patch.object(local, 'environment') as environment, patch.object(pg, 'doctor') as doctor:
                args = argparse.Namespace(command=name, provider='postgresql', pg_config='secret', release_dir='private',
                    output_dir=None, backend_only=False, confirm_delete_local_data=False)
                with self.assertRaises(RuntimeError):pg.dispatch(local,args)
                environment.assert_not_called();doctor.assert_not_called()

    def test_existing_mysql_dispatch_still_requires_its_environment(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(local,'STATE',Path(temporary)), patch('sys.argv',['local_stack.py','doctor']), patch.object(local,'environment',return_value={'legacy':'yes'}) as env, patch.object(local,'doctor') as doctor:
            local.main()
            env.assert_called_once();doctor.assert_called_once_with({'legacy':'yes'})

    def test_pg_cli_routes_without_loading_mysql_env(self):
        with patch('sys.argv',['local_stack.py','doctor','--provider','postgresql','--pg-config','x','--release-dir','y']), patch.object(local,'environment') as env, patch.object(pg,'dispatch') as dispatch:
            local.main()
            env.assert_not_called();self.assertEqual(dispatch.call_args.args[1].provider,'postgresql')

    def test_demo_requires_explicit_pg_config_and_never_loads_mysql(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(local, 'STATE', Path(temporary)), \
                patch.object(local, 'environment') as legacy, patch.object(pg, 'start') as start:
            for options in ([], ['--pg-config', 'demo-runtime'], ['--release-dir', 'release'],
                            ['--provider', 'mysql', '--pg-config', 'demo-runtime', '--release-dir', 'release']):
                with self.subTest(options=options), patch('sys.argv', ['local_stack.py', 'demo', *options]):
                    with self.assertRaises(RuntimeError):
                        local.main()
            start.assert_not_called()
            with patch('sys.argv', ['local_stack.py', 'demo', '--pg-config', 'demo-runtime', '--release-dir', 'release']):
                local.main()
            args = start.call_args.args[1]
            self.assertEqual((args.command, args.pg_config, args.release_dir), ('demo', 'demo-runtime', 'release'))
            legacy.assert_not_called()

    def test_demo_rejects_operating_copies_and_ports_before_inspecting_docker(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scope = types.SimpleNamespace(STATE=root)
            operating, directory = self.selection_fixture(root)
            current = dict(self.env(), PUBBURI_SEARCH_ENABLED='true')
            operating.unlink()
            pg.write_json(operating, {'schema_version': 1, 'environment': current})
            pg.publish_selection(scope, operating, directory, pg.selection_pins(operating, directory))
            selector = root / pg.DEFAULT_SELECTION
            selected = selector.read_bytes()
            demo = root / 'demo.json'
            separate = dict(self.env(), PUBBURI_PG_CONTAINER_ID='b' * 64, PUBBURI_PG_DB_NAME='demo_ci',
                PUBBURI_PG_URL='jdbc:postgresql://127.0.0.1:26432/demo_ci', PUBBURI_PG_RUN_TOKEN='authored-demo-marker',
                PUBBURI_JPA_SOURCE_ID='87654321-1234-1234-1234-123456789abc',
                PUBBURI_SERVER_PORT='16001', PUBBURI_FRONTEND_PORT='16002')

            for change in (dict(PUBBURI_PG_CONTAINER_ID=current['PUBBURI_PG_CONTAINER_ID'][:12]),
                           dict(PUBBURI_JPA_SOURCE_ID=current['PUBBURI_JPA_SOURCE_ID']),
                           dict(PUBBURI_FRONTEND_PORT=current['PUBBURI_SERVER_PORT']),
                           dict(PUBBURI_SERVER_PORT='25432'), dict(PUBBURI_FRONTEND_PORT='26432'),
                           dict(PUBBURI_SEARCH_ENABLED='true'),
                           dict(PUBBURI_SEARCH_ENABLED='true', PUBBURI_SEARCH_ENDPOINT='http://127.0.0.1:9200/')):
                with self.subTest(change=change), patch.object(pg, 'release') as release, \
                        patch.object(pg, 'verify_owned_postgres') as inspect:
                    demo.unlink(missing_ok=True)
                    pg.write_json(demo, {'schema_version': 1, 'environment': dict(separate, **change)})
                    with self.assertRaises(RuntimeError):
                        pg.doctor(scope, demo, directory, True, demo=True)
                    release.assert_not_called()
                    inspect.assert_not_called()

            separate.update(PUBBURI_SEARCH_ENABLED='true', PUBBURI_SEARCH_ENDPOINT='http://127.0.0.1:19200')
            demo.unlink()
            pg.write_json(demo, {'schema_version': 1, 'environment': separate})
            with patch.object(pg, 'release', return_value=(directory, {})), patch.object(pg, 'java17', return_value={}), \
                    patch.object(pg, 'verify_owned_postgres') as inspect:
                verified, artifact, _ = pg.doctor(scope, demo, directory, True, demo=True)
                self.assertEqual((verified, artifact), (separate, directory))
                inspect.assert_called_once_with(separate, child_env={})
                self.assertEqual(selector.read_bytes(), selected)
                selector.unlink()
                verified, _, _ = pg.doctor(scope, demo, directory, True, demo=True)
                self.assertEqual(verified, separate)
                self.assertFalse(selector.exists())

    def test_dev_uses_selected_pg_backend_only_and_rejects_an_unreadable_selector(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config, release = self.selection_fixture(root)
            pg.publish_selection(types.SimpleNamespace(STATE=root), config, release, pg.selection_pins(config, release))
            with patch.object(local, 'STATE', root), patch('sys.argv', ['local_stack.py', 'dev']), \
                    patch.object(local.os, 'execv') as legacy, patch.object(pg, 'dispatch') as dispatch:
                local.main()
                args = dispatch.call_args.args[1]
                self.assertEqual((args.command, args.backend_only, args.pg_config, args.release_dir),
                                 ('up', True, str(config), str(release)))
                dispatch.reset_mock()
                with patch.object(Path, 'lstat', side_effect=PermissionError):
                    with self.assertRaises(PermissionError):
                        local.main()
                legacy.assert_not_called()
                dispatch.assert_not_called()

    def test_dev_shell_reads_legacy_env_only_when_legacy_is_selected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / 'scripts'
            scripts.mkdir()
            for name in ('dev.sh', 'local-stack.sh', 'local_stack.py', 'postgresql_local.py',
                         'postgresql_schema.py', 'jpa_smoke.py', 'search_index.py', 'api_smoke.py'):
                shutil.copy2(local.BACKEND / 'scripts' / name, scripts / name)
            wrapper = root / 'mvnw'
            wrapper.write_text('#!/usr/bin/env bash\nprintf "%s %s\\n" "$*" "$DEV_TEST_VALUE"\n')
            wrapper.chmod(0o700)
            (root / '.env').write_text('touch legacy-env-read\nDEV_TEST_VALUE=from-dotenv\n')
            marker = root / 'legacy-env-read'
            command = [str(scripts / 'dev.sh')]

            self.assertEqual(subprocess.check_output(command, cwd=root, text=True),
                             '-f tools/mysql-baseline/pom.xml spring-boot:run from-dotenv\n')
            self.assertTrue(marker.is_file())
            marker.unlink()
            state = root / '.pubburi'
            state.mkdir()
            pg.write_json(state / pg.DEFAULT_SELECTION, {})
            rejected = subprocess.run(command, cwd=root, capture_output=True, text=True)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn('PostgreSQL local launch rejected', rejected.stderr)
            self.assertFalse(marker.exists())
            self.assertEqual(subprocess.check_output([*command, '--provider', 'mysql'], cwd=root, text=True),
                             '-f tools/mysql-baseline/pom.xml spring-boot:run from-dotenv\n')
            self.assertTrue(marker.is_file())

    def selection_fixture(self, root):
        config=self.config(root);directory=root/'release';directory.mkdir(mode=0o700)
        for name in ('manifest.json','runtime.jar'):
            (directory/name).write_bytes(b'private-fixture');(directory/name).chmod(0o600)
        return config,directory

    def test_selected_default_never_loads_mysql_and_explicit_baseline_remains_available(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);scope=types.SimpleNamespace(STATE=root)
            config,directory=self.selection_fixture(root)
            pg.publish_selection(scope,config,directory,pg.selection_pins(config,directory))
            selector=root/pg.DEFAULT_SELECTION
            self.assertEqual(selector.stat().st_mode&0o777,0o600)
            self.assertNotIn('runtime-secret',selector.read_text())
            with patch.object(local,'STATE',root), patch.object(local,'environment') as environment, patch.object(pg,'dispatch') as dispatch:
                with patch('sys.argv',['local_stack.py','doctor']):local.main()
                environment.assert_not_called()
                args=dispatch.call_args.args[1]
                self.assertEqual((args.provider,args.pg_config,args.release_dir),('postgresql',str(config),str(directory)))
            for name in ('init','install','down','reset-db'):
                with self.subTest(command=name), patch.object(local,'STATE',root), patch.object(local,'environment') as environment, patch.object(local,'compose') as compose, patch('sys.argv',['local_stack.py',name]):
                    with self.assertRaises(RuntimeError):local.main()
                    environment.assert_not_called();compose.assert_not_called()
            with patch.object(local,'STATE',root), patch.object(local,'environment',return_value={}) as environment, patch.object(local,'doctor') as doctor, patch('sys.argv',['local_stack.py','doctor','--provider','mysql']):
                local.main();environment.assert_called_once();doctor.assert_called_once()

    def test_changed_missing_or_unsafe_selection_never_falls_back(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);scope=types.SimpleNamespace(STATE=root)
            config,directory=self.selection_fixture(root);pins=pg.selection_pins(config,directory)
            pg.publish_selection(scope,config,directory,pins);path=root/pg.DEFAULT_SELECTION;original=path.read_bytes()
            for target in (config,directory/'manifest.json',directory/'runtime.jar'):
                before=target.read_bytes();target.write_bytes(before+b'changed')
                with self.subTest(target=target.name), patch.object(local,'STATE',root), patch.object(local,'environment') as environment, patch.object(local,'compose') as compose, patch('sys.argv',['local_stack.py','doctor']):
                    with self.assertRaises(RuntimeError):local.main()
                    environment.assert_not_called();compose.assert_not_called()
                target.write_bytes(before)
            for body in (b'{}',b'{"schema_version":1,"schema_version":1}',b'NaN'):
                path.write_bytes(body)
                with self.assertRaises(RuntimeError):pg.selected(scope,argparse.Namespace(pg_config=None,release_dir=None))
            path.write_bytes(original);path.chmod(0o644)
            with self.assertRaises(RuntimeError):pg.selected(scope,argparse.Namespace(pg_config=None,release_dir=None))
            path.chmod(0o600);path.unlink();path.symlink_to(root/'missing')
            with self.assertRaises(OSError):pg.selected(scope,argparse.Namespace(pg_config=None,release_dir=None))
            path.unlink();path.write_bytes(original);path.chmod(0o600)
            with self.assertRaises(RuntimeError):pg.selected(scope,argparse.Namespace(pg_config=str(config),release_dir=None))
            with patch.object(pg.os,'replace',side_effect=OSError('publish interrupted')):
                with self.assertRaises(OSError):pg.publish_selection(scope,config,directory,pins)
            self.assertEqual(path.read_bytes(),original)
            self.assertEqual(list(root.glob('.local-provider-*.tmp')),[])

    def test_concurrent_selection_or_input_change_rejects_under_the_start_lock(self):
        for during_doctor in (False,True):
            with self.subTest(during_doctor=during_doctor), tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary);scope=types.SimpleNamespace(STATE=root)
                config,directory=self.selection_fixture(root)
                pg.publish_selection(scope,config,directory,pg.selection_pins(config,directory))
                args=argparse.Namespace(pg_config=None,release_dir=None,command='smoke',backend_only=True)
                self.assertTrue(pg.selected(scope,args))
                def doctor(*a):
                    self.assertTrue((root/'local-stack-lock').is_dir())
                    config.write_bytes(config.read_bytes()+b' ')
                    return self.env(),directory,{}
                if not during_doctor:
                    config.write_bytes(config.read_bytes()+b' ')
                    pg.publish_selection(scope,config,directory,pg.selection_pins(config,directory))
                with patch.object(local,'STATE',root), patch.object(pg,'doctor',side_effect=doctor) as checked, patch.object(pg.subprocess,'Popen') as spawn:
                    with self.assertRaises(RuntimeError):pg.start(local,args)
                    self.assertEqual(checked.call_count,int(during_doctor));spawn.assert_not_called()
                self.assertFalse((root/'local-stack-lock').exists())

    def test_activation_cannot_replace_the_selected_database(self):
        changes = (
            {'PUBBURI_PG_DB_NAME': 'older_ci', 'PUBBURI_PG_URL': 'jdbc:postgresql://127.0.0.1:25432/older_ci'},
            {'PUBBURI_PG_CONTAINER_ID': 'b' * 64},
            {'PUBBURI_PG_RUN_TOKEN': 'different-owned-marker'},
            {'PUBBURI_JPA_SOURCE_ID': '87654321-1234-1234-1234-123456789abc'},
        )
        for change in changes:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                config, release = self.selection_fixture(root)
                selector = root / pg.DEFAULT_SELECTION
                pg.publish_selection(types.SimpleNamespace(STATE=root), config, release,
                    pg.selection_pins(config, release))
                before = selector.read_bytes()
                candidate = root / 'candidate.json'
                with socket.socket() as port:
                    port.bind(('127.0.0.1', 0))
                    env = dict(self.env(), **change, PUBBURI_SERVER_PORT=str(port.getsockname()[1]))
                pg.write_json(candidate, {'schema_version': 1, 'environment': env})
                args = argparse.Namespace(command='activate-pg', pg_config=candidate, release_dir=release,
                    backend_only=True)
                checked = patch.object(pg, 'doctor',
                    return_value=(env, release, {'jar_sha256': pg.sha(release / 'runtime.jar')}))
                launched = patch.object(pg.subprocess, 'Popen',
                    side_effect=AssertionError('candidate JVM started'))
                with patch.object(local, 'STATE', root), checked, launched as spawn:
                    with self.assertRaisesRegex(RuntimeError, 'selected PostgreSQL database'):
                        pg.start(local, args)
                    spawn.assert_not_called()
                self.assertEqual(selector.read_bytes(), before)
                self.assertEqual(list(root.glob('pg-run-*')), [])

    def test_activation_publishes_only_after_readiness_and_owned_cleanup_under_the_lock(self):
        for cleanup_fails in (False,True):
            with self.subTest(cleanup_fails=cleanup_fails), tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary);config,directory=self.selection_fixture(root)
                env=self.env()
                with socket.socket() as server:
                    server.bind(('127.0.0.1',0));env['PUBBURI_SERVER_PORT']=str(server.getsockname()[1])
                args=argparse.Namespace(pg_config=config,release_dir=directory,backend_only=True,command='activate-pg')
                child=MagicMock();child.poll.return_value=None
                def spawn(*a,**kw):
                    kw['stdout'].write('Tomcat started on port '+env['PUBBURI_SERVER_PORT']+' ');kw['stdout'].flush();return child
                def stop(process):
                    self.assertTrue((root/'local-stack-lock').is_dir())
                    self.assertFalse((root/pg.DEFAULT_SELECTION).exists())
                    if cleanup_fails:raise local.ProcessCleanupError('owned child remains')
                with patch.object(local,'STATE',root), patch.object(pg,'doctor',return_value=(env,directory,{'jar_sha256':pg.sha(directory/'runtime.jar')})), patch.object(pg.subprocess,'Popen',side_effect=spawn), patch.object(pg,'api_ready') as ready, patch.object(pg,'initial_cutover',return_value='a'*64) as proof, patch.object(local,'stop',side_effect=stop):
                    if cleanup_fails:
                        with self.assertRaises(local.ProcessCleanupError):pg.start(local,args)
                    else:pg.start(local,args)
                    ready.assert_called_once()
                    self.assertEqual(proof.call_count,1 if cleanup_fails else 2)
                self.assertEqual((root/pg.DEFAULT_SELECTION).exists(),not cleanup_fails)
                self.assertEqual((root/'local-stack-lock').exists(),cleanup_fails)

    def test_initial_cutover_configuration_is_exact_bound_private_and_never_an_app_environment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);env=self.env();path=root/'cutover.json'
            proof={key:'authored' for key in importing.CUTOVER_REQUIRED}
            for key in (*pg.DATABASE_IDENTITY[:-1],'PUBBURI_PG_URL','PUBBURI_PG_CA'):proof[key]=env[key]
            proof['PUBBURI_PG_IMPORT_SOURCE_ID']=env['PUBBURI_JPA_SOURCE_ID']
            pg.write_json(path,{'schema_version':1,'environment':proof})
            with patch.object(importing,'execute',return_value=types.SimpleNamespace(returncode=0)) as verify:
                pin=pg.initial_cutover(path,env)
                self.assertEqual(verify.call_args.args[1],'verify-cutover')
                self.assertEqual(verify.call_args.args[0]['PUBBURI_SOURCE_PASSWORD'],'authored')
                self.assertNotIn('PUBBURI_SOURCE_PASSWORD',pg.child_environments(env)[0])
                pg.initial_cutover(path,env,pin)
                path.write_bytes(path.read_bytes()+b' ')
                with self.assertRaises(RuntimeError):pg.initial_cutover(path,env,pin)
                self.assertEqual(verify.call_count,2)
            for mutation in ({'PUBBURI_PG_URL':'jdbc:postgresql://127.0.0.1:25432/other_ci'},
                             {'PUBBURI_PG_RUN_TOKEN':'different'}, {'PUBBURI_PG_IMPORT_SOURCE_ID':'other'},
                             {'PUBBURI_PG_MUTATING':'1'}, {'JAVA_TOOL_OPTIONS':'untrusted'}):
                path.unlink()
                pg.write_json(path,{'schema_version':1,'environment':dict(proof,**mutation)})
                with patch.object(importing,'execute') as verify:
                    with self.assertRaises(RuntimeError):pg.initial_cutover(path,env)
                    verify.assert_not_called()
            path.unlink();pg.write_json(path,{'schema_version':1,'environment':proof});path.chmod(0o644)
            with self.assertRaises(RuntimeError):pg.initial_cutover(path,env)
            with self.assertRaises(RuntimeError):pg.initial_cutover(None,env)

    def test_initial_cutover_drift_before_launch_or_after_smoke_never_publishes(self):
        for drift_check in (1,2):
            with self.subTest(drift_check=drift_check), tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary);config,directory=self.selection_fixture(root);env=self.env()
                with socket.socket() as server:
                    server.bind(('127.0.0.1',0));env['PUBBURI_SERVER_PORT']=str(server.getsockname()[1])
                args=argparse.Namespace(pg_config=config,release_dir=directory,backend_only=True,command='activate-pg',cutover_config='private-proof')
                child=MagicMock();child.poll.return_value=None
                def spawn(*a,**kw):
                    kw['stdout'].write('Tomcat started on port '+env['PUBBURI_SERVER_PORT']+' ');kw['stdout'].flush();return child
                checks=[RuntimeError('source drift')] if drift_check==1 else ['pin',RuntimeError('source drift')]
                with patch.object(local,'STATE',root),patch.object(pg,'doctor',return_value=(env,directory,{'jar_sha256':pg.sha(directory/'runtime.jar')})), \
                     patch.object(pg,'initial_cutover',side_effect=checks) as verify,patch.object(pg.subprocess,'Popen',side_effect=spawn) as started, \
                     patch.object(pg,'api_ready'),patch.object(local,'stop') as stopped:
                    with self.assertRaisesRegex(RuntimeError,'source drift'):pg.start(local,args)
                    self.assertEqual(started.call_count,drift_check-1);self.assertEqual(stopped.call_count,drift_check-1)
                    self.assertEqual(verify.call_count,drift_check)
                self.assertFalse((root/pg.DEFAULT_SELECTION).exists());self.assertFalse((root/'local-stack-lock').exists())

    def test_private_directory_refuses_reuse_external_and_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); scope=types.SimpleNamespace(STATE=root/'.pubburi')
            path=pg.private_directory(scope.STATE/'new',scope,True)
            with self.assertRaises(FileExistsError):pg.private_directory(path,scope,True)
            with self.assertRaises(RuntimeError):pg.private_directory(root/'external',scope,True)
            (scope.STATE/'linked').symlink_to(path)
            with self.assertRaises(RuntimeError):pg.private_directory(scope.STATE/'linked',scope)

    def test_real_git_provenance_rejects_dirty_and_untracked_artifact_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'src/main').mkdir(parents=True); file=root/'src/main/example';file.write_text('original')
            subprocess.run(['git','init','-q',str(root)],check=True)
            subprocess.run(['git','add','.'],cwd=root,check=True)
            subprocess.run(['git','-c','user.email=fixture@example.invalid','-c','user.name=Fixture','commit','-qm','fixture'],cwd=root,check=True)
            scope=types.SimpleNamespace(BACKEND=root)
            original=pg.source_files(scope)
            self.assertEqual(original['src/main/example'],hashlib.sha256(b'original').hexdigest())
            file.write_text('changed')
            with self.assertRaises(RuntimeError):pg.source_files(scope)
            file.write_text('original');file.chmod(0o755)
            with self.assertRaises(RuntimeError):pg.source_files(scope)
            file.chmod(0o644);(root/'src/main/extra').write_text('untracked')
            with self.assertRaises(RuntimeError):pg.source_files(scope)

    def test_jar_refuses_legacy_runtime_and_wrong_entrypoint(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'app.jar'
            entry='BOOT-INF/classes/com/pubburi/rehearsal/JpaRehearsalApplication.class'
            manifest='Main-Class: org.springframework.boot.loader.launch.JarLauncher\nStart-Class: com.pubburi.rehearsal.JpaRehearsalApplication\n'
            with zipfile.ZipFile(path,'w') as jar:
                jar.writestr('META-INF/MANIFEST.MF',manifest)
                for name in (entry,'BOOT-INF/lib/spring-data-jpa-fixture.jar','BOOT-INF/lib/hibernate-core-fixture.jar'):
                    jar.writestr(name,b'fixture')
            pg.validate_jar(path)
            for invalid in ('Start-Class: com.pubburi.pub.ServerApplication\n',manifest+'Start-Class: com.pubburi.pub.ServerApplication\n'):
                with zipfile.ZipFile(path,'w') as jar:
                    jar.writestr('META-INF/MANIFEST.MF',invalid)
                    jar.writestr(entry,b'fixture')
                with self.assertRaises(ValueError):pg.validate_jar(path)
            with zipfile.ZipFile(path,'w') as jar:
                jar.writestr('META-INF/MANIFEST.MF',manifest)
                for name in (entry,'BOOT-INF/lib/spring-data-jpa-fixture.jar','BOOT-INF/lib/hibernate-core-fixture.jar'):
                    jar.writestr(name,b'fixture')
            for forbidden in ('BOOT-INF/classes/mappers/product.xml','BOOT-INF/classes/com/pubburi/pub/controller/rest/UnavailableNotebookController.class'):
                with zipfile.ZipFile(path,'w') as jar:
                    jar.writestr('META-INF/MANIFEST.MF',manifest)
                    for name in (entry,'BOOT-INF/lib/spring-data-jpa-fixture.jar','BOOT-INF/lib/hibernate-core-fixture.jar',forbidden):
                        jar.writestr(name,b'fixture')
                with self.assertRaises(ValueError):pg.validate_jar(path)
            with zipfile.ZipFile(path,'w') as jar:
                jar.writestr('META-INF/MANIFEST.MF',manifest)
                for name in (entry,'BOOT-INF/lib/spring-data-jpa-fixture.jar','BOOT-INF/lib/hibernate-core-fixture.jar'):
                    jar.writestr(name,b'fixture')
            with zipfile.ZipFile(path,'a') as jar:jar.writestr('BOOT-INF/lib/mysql-connector-j.jar',b'legacy')
            with self.assertRaises(ValueError):pg.validate_jar(path)

    def test_closed_server_time_wait_is_not_an_active_listener(self):
        with tempfile.TemporaryDirectory() as temporary:
            server=socket.socket();server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            server.bind(('127.0.0.1',0));server.listen();port=server.getsockname()[1]
            client=socket.create_connection(('127.0.0.1',port),timeout=2)
            accepted,_=server.accept()
            accepted.shutdown(socket.SHUT_WR)
            self.assertEqual(client.recv(1),b'')  # Server FIN arrives before client closes.
            client.close();accepted.close();server.close()
            with socket.socket() as plain:
                with self.assertRaises(OSError):plain.bind(('127.0.0.1',port))
            root=Path(temporary);env=self.env();env['PUBBURI_SERVER_PORT']=str(port)
            args=argparse.Namespace(pg_config='x',release_dir='x',backend_only=True,command='smoke')
            with patch.object(local,'STATE',root/'.pubburi'), patch.object(pg,'doctor',return_value=(env,root,{})), patch.object(pg,'private_directory',side_effect=RuntimeError('past-port-check')), patch.object(pg.subprocess,'Popen') as popen:
                with self.assertRaisesRegex(RuntimeError,'past-port-check'):pg.start(local,args)
                popen.assert_not_called()
                self.assertFalse((local.STATE/'local-stack-lock').exists())

    def test_occupied_port_is_preserved_before_app_process_or_copy(self):
        with tempfile.TemporaryDirectory() as temporary, socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            listener.bind(('127.0.0.1',0));listener.listen()
            root=Path(temporary);env=self.env();env['PUBBURI_SERVER_PORT']=str(listener.getsockname()[1])
            args=argparse.Namespace(pg_config='x',release_dir='x',backend_only=True,command='smoke')
            with patch.object(local,'STATE',root/'.pubburi'), patch.object(pg,'doctor',return_value=(env,root,{})), patch.object(pg.subprocess,'Popen') as popen:
                with self.assertRaises(OSError):pg.start(local,args)
                popen.assert_not_called()
                self.assertFalse((local.STATE/'local-stack-lock').exists())
            self.assertEqual(listener.getsockname()[1],int(env['PUBBURI_SERVER_PORT']))

    def test_docker_ownership_receives_only_sanitized_environment(self):
        with patch.object(pg,'configuration',return_value=self.env()), patch.object(pg,'release',return_value=(Path('/release'),{})), patch.object(pg,'java17',return_value={'PATH':'/tools'}), patch.object(pg,'verify_owned_postgres') as owned:
            pg.doctor(local,'config','release',True)
            owned.assert_called_once_with(self.env(),child_env={'PATH':'/tools'})

    def test_ci_uses_runtime_only_projection_and_keeps_all_prior_grants(self):
        import jpa_rehearsal_ci as ci
        with tempfile.TemporaryDirectory() as temporary:
            state=Path(temporary)/'.pubburi';state.mkdir(mode=0o700)
            env=dict(self.env(),PUBBURI_JPA_CI='1',PUBBURI_JPA_CONTROL_PASSWORD='never-child',PUBBURI_PG_MUTATING='1')
            def stop_before_cli(scope,args):
                self.assertNotEqual(scope.STATE,state);self.assertEqual(scope.STATE.parent,state)
                config=pg.configuration(args.pg_config)
                self.assertEqual(set(config),pg.REQUIRED)
                self.assertNotIn('PUBBURI_JPA_CONTROL_PASSWORD',config)
                self.assertNotIn('PUBBURI_PG_MUTATING',config)
                self.assertTrue(args.backend_only);self.assertEqual(args.command,'activate-pg')
                raise RuntimeError('projection verified')
            with patch.object(local,'STATE',state), patch.object(pg,'package') as package, patch.object(pg,'start',side_effect=stop_before_cli):
                with self.assertRaisesRegex(RuntimeError,'projection verified'):ci.local_release(env,{key:'authored' for key in importing.CUTOVER_REQUIRED})
            package.assert_called_once()

    def test_doctor_and_status_observe_while_up_holds_the_lifecycle_lock(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            args=argparse.Namespace(command='status',pg_config='fixture',release_dir='fixture',output_dir=None,backend_only=True,confirm_delete_local_data=False)
            with patch.object(local,'STATE',root), patch.object(pg,'doctor',return_value=(self.env(),root,{})) as doctor, patch.object(pg,'api_ready') as ready, local.operation_lock():
                pg.dispatch(local,args)
                args.command='doctor';pg.dispatch(local,args)
                self.assertEqual(doctor.call_count,2);ready.assert_called_once()
                self.assertTrue((root/'local-stack-lock').is_dir())

    def test_runtime_identity_pins_the_canonical_inspected_container(self):
        environment = dict(self.env(), PUBBURI_PG_CONTAINER_ID='a' * 12,
            PUBBURI_SEARCH_ENABLED='true', PUBBURI_SEARCH_ENDPOINT='http://127.0.0.1:19200')
        with patch.object(local, 'run', return_value=types.SimpleNamespace(stdout='a' * 64 + '\n')) as inspected:
            identity = pg.runtime_identity(local, environment)
        self.assertEqual(identity['container_id'], 'a' * 64)
        self.assertEqual(set(identity['ports']), {25432, 15001, 15002, 19200})
        self.assertNotIn(environment['PUBBURI_PG_PASSWORD'], json.dumps(identity))
        self.assertEqual(inspected.call_args.kwargs['env'], pg.base_environment())
        with patch.object(local, 'run', return_value=types.SimpleNamespace(stdout='b' * 64 + '\n')):
            with self.assertRaises(RuntimeError):
                pg.runtime_identity(local, environment)

    def test_up_and_demo_use_the_scoped_owner_until_owned_children_stop(self):
        for command in ('up', 'demo'):
            with self.subTest(command=command), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); private = root / 'private'; directory = root / 'release'
                private.mkdir(mode=0o700); directory.mkdir(mode=0o700)
                (directory / 'runtime.jar').write_bytes(b'authored isolated lifecycle artifact')
                environment = self.env(); args = argparse.Namespace(command=command, pg_config='fixture',
                    release_dir='fixture', backend_only=True)
                scope = types.SimpleNamespace(**vars(local)); scope.STATE = private
                scope.operation_lock = lambda: local.operation_lock(private)
                scope.run = MagicMock(return_value=types.SimpleNamespace(stdout='a' * 64 + '\n'))
                child = MagicMock(); child.poll.return_value = None
                def spawn(*a, **kw):
                    kw['stdout'].write('Tomcat started on port 15001 ready\n'); kw['stdout'].flush()
                    return child
                def stop(owned):
                    self.assertIs(owned, child)
                    with local.operation_gate(private) as parent:
                        self.assertEqual(set(local.runtime_records(parent, live=True)),
                                         {'demo' if command == 'demo' else 'operating'})
                scope.stop = MagicMock(side_effect=stop)
                with patch.object(local, 'STATE', root / 'forbidden'), \
                        patch.object(pg, 'doctor', return_value=(environment, directory,
                            {'jar_sha256': pg.sha(directory / 'runtime.jar')})), \
                        patch.object(pg, 'api_ready'), patch.object(pg.subprocess, 'Popen', side_effect=spawn), \
                        patch.object(pg.time, 'sleep', side_effect=KeyboardInterrupt):
                    with self.assertRaises(KeyboardInterrupt):
                        pg.start(scope, args)
                scope.stop.assert_called_once_with(child)
                self.assertFalse((root / 'forbidden').exists())
                with local.operation_lock(private):
                    pass

    def test_live_demo_without_a_selector_blocks_conflicting_operating_before_spawn(self):
        for conflict in ('canonical-container', 'source', 'database-port', 'api-port', 'web-port', 'search-port'):
            with self.subTest(conflict=conflict), tempfile.TemporaryDirectory() as temporary:
                state = Path(temporary)
                environment = dict(self.env(), PUBBURI_PG_CONTAINER_ID='a' * 12,
                    PUBBURI_SEARCH_ENABLED='true', PUBBURI_SEARCH_ENDPOINT='http://127.0.0.1:19200')
                peer = dict(container_id='b' * 64, source_id='87654321-1234-1234-1234-123456789abc',
                    database_name='owned_ci', database_port=26432, ports=[26432, 16001, 16002, 29200], marker_sha256='d' * 64)
                if conflict == 'canonical-container': peer['container_id'] = 'a' * 64
                elif conflict == 'source': peer['source_id'] = environment['PUBBURI_JPA_SOURCE_ID']
                elif conflict == 'database-port': peer.update(database_port=25432, ports=[25432, 16001, 16002, 29200])
                elif conflict == 'api-port': peer['ports'][1] = 15001
                elif conflict == 'web-port': peer['ports'][2] = 15002
                else: peer['ports'][3] = 19200
                scope = types.SimpleNamespace(**vars(local)); scope.STATE = state
                scope.run = MagicMock(return_value=types.SimpleNamespace(stdout='a' * 64 + '\n'))
                args = argparse.Namespace(command='up', pg_config='fixture', release_dir='fixture', backend_only=True)
                with local.runtime_lock(state, 'demo', lambda: (peer, None)), \
                        patch.object(pg, 'doctor', return_value=(environment, state, {})), \
                        patch.object(pg.subprocess, 'Popen') as spawn:
                    with self.assertRaisesRegex(RuntimeError, 'separate containers'):
                        pg.start(scope, args)
                    spawn.assert_not_called()
                    self.assertFalse((state / pg.DEFAULT_SELECTION).exists())

    def test_live_runtime_fences_existing_exclusive_entrypoints_without_touching_their_resources(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary); scope = types.SimpleNamespace(**vars(local)); scope.STATE = state
            scope.operation_lock = lambda: local.operation_lock(state)
            identity = dict(container_id='a' * 64, source_id=self.env()['PUBBURI_JPA_SOURCE_ID'],
                database_name='owned_ci', database_port=25432, ports=[25432, 15001, 15002], marker_sha256='d' * 64)
            with local.runtime_lock(state, 'operating', lambda: (identity, None)), \
                    patch.object(pg, 'doctor') as doctor, patch.object(pg, 'private_directory') as directory:
                for command in ('smoke', 'activate-pg'):
                    args = argparse.Namespace(command=command, pg_config='fixture', release_dir='fixture', backend_only=True)
                    with self.subTest(command=command), self.assertRaisesRegex(RuntimeError, 'already owned'):
                        pg.start(scope, args)
                with self.assertRaisesRegex(RuntimeError, 'already owned'):
                    pg.package(scope, state / 'release')
                doctor.assert_not_called(); directory.assert_not_called()
                import postgresql_recovery as recovery
                with patch.object(recovery.private, 'selection_record') as selected, \
                        patch.object(recovery, 'verify_owned_postgres') as owned:
                    with self.assertRaisesRegex(RuntimeError, 'already owned'):
                        recovery.verify_preserved(scope, {}, {}, state / 'archive', 'a' * 64,
                            state / 'restored', 'b' * 64)
                    with self.assertRaisesRegex(RuntimeError, 'already owned'):
                        recovery.promote(scope, {}, {}, state / 'archive', 'a' * 64,
                            state / 'restored', 'b' * 64, state / 'config', state / 'release')
                    selected.assert_not_called(); owned.assert_not_called()
                for command in ('down', 'reset-db', 'up'):
                    with self.subTest(command=command), patch.object(local, 'STATE', state), \
                            patch.object(local, 'environment', return_value={}), patch.object(local, 'doctor'), \
                            patch.object(local, 'FRONTEND', state), patch.object(local, 'compose') as compose, \
                            patch('sys.argv', ['local_stack.py', command, '--provider', 'mysql', '--confirm-delete-local-data']):
                        (state / 'node_modules').mkdir(exist_ok=True)
                        with self.assertRaisesRegex(RuntimeError, 'already owned'):
                            local.main()
                        compose.assert_not_called()

    def test_doctor_and_status_do_not_adopt_or_change_a_live_durable_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary); scope = types.SimpleNamespace(**vars(local)); scope.STATE = state
            identity = dict(container_id='a' * 64, source_id=self.env()['PUBBURI_JPA_SOURCE_ID'],
                database_name='owned_ci', database_port=25432, ports=[25432, 15001, 15002], marker_sha256='d' * 64)
            args = argparse.Namespace(command='status', pg_config='fixture', release_dir='fixture',
                output_dir=None, backend_only=True, confirm_delete_local_data=False)
            with local.runtime_lock(state, 'operating', lambda: (identity, None)), \
                    patch.object(pg, 'doctor', return_value=(self.env(), state, {})) as doctor, \
                    patch.object(pg, 'api_ready') as ready:
                original = (state / local.RUNTIME_REGISTRY).read_bytes()
                pg.dispatch(scope, args); args.command = 'doctor'; pg.dispatch(scope, args)
                self.assertEqual((state / local.RUNTIME_REGISTRY).read_bytes(), original)
                self.assertEqual(doctor.call_count, 2); ready.assert_called_once()

    def test_readiness_rejects_missing_error_and_wrong_registered_scope(self):
        response=MagicMock();response.__enter__.return_value=response
        response.read.return_value=json.dumps({'data':{'items':[]}}).encode()
        with patch.object(pg.urllib.request,'urlopen',return_value=response):
            with self.assertRaises((RuntimeError,ValueError)):pg.api_ready('http://127.0.0.1:15001')
        response.read.side_effect=[json.dumps({'data':{'items':[]},'error':None}).encode(),b'{"paths":{}}']
        with patch.object(pg.urllib.request,'urlopen',return_value=response):
            with self.assertRaises((RuntimeError,ValueError)):pg.api_ready('http://127.0.0.1:15001')

    def test_release_checks_real_git_tree_and_actual_jar_pin(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'src/main').mkdir(parents=True)
            file=root/'src/main/code';file.write_text('first')
            def git(*args):
                return subprocess.check_output(['git',*args],cwd=root,text=True).strip()
            git('init','-q');git('config','user.email','fixture@example.invalid');git('config','user.name','Fixture')
            git('add','.');git('commit','-qm','first');first=git('rev-parse','HEAD')
            file.write_text('second');git('add','.');git('commit','-qm','second');current=git('rev-parse','HEAD')
            scope=types.SimpleNamespace(STATE=root/'.pubburi',BACKEND=root)
            directory=pg.private_directory(scope.STATE/'release',scope,True)
            jar=directory/'runtime.jar'
            with zipfile.ZipFile(jar,'w') as archive:
                archive.writestr('META-INF/MANIFEST.MF','Main-Class: org.springframework.boot.loader.launch.JarLauncher\nStart-Class: com.pubburi.rehearsal.JpaRehearsalApplication\n')
                for name in ('BOOT-INF/classes/com/pubburi/rehearsal/JpaRehearsalApplication.class',
                             'BOOT-INF/lib/spring-data-jpa-fixture.jar','BOOT-INF/lib/hibernate-core-fixture.jar'):
                    archive.writestr(name,b'fixture')
            jar.chmod(0o600)
            value={'schema_version':1,'completed':True,'provider':'JPA_POSTGRESQL_REHEARSAL','source_commit':first,
                'source_files':pg.source_files(scope),'jar_sha256':pg.sha(jar),'java_feature':17,'postgresql_version':'18.6',
                'api_paths':48,'api_operations':60,'preparation':'database-independent-maven-verify'}
            def manifest(commit):
                (directory/'manifest.json').unlink(missing_ok=True)
                pg.write_json(directory/'manifest.json',dict(value,source_commit=commit))
            manifest(first)
            with self.assertRaises(RuntimeError):pg.release(directory,scope)
            manifest('f'*40)
            with self.assertRaises(RuntimeError):pg.release(directory,scope)
            manifest(current);self.assertEqual(pg.release(directory,scope)[1]['source_commit'],current)
            with jar.open('ab') as output:output.write(b'tamper')
            with self.assertRaises(RuntimeError):pg.release(directory,scope)


if __name__=='__main__':unittest.main()
