"""Local PostgreSQL release ownership, provenance and secret boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch, MagicMock
import zipfile

import local_stack as local
import postgresql_local as pg


class PostgreSQLLocalTests(unittest.TestCase):
    def env(self):
        return dict(PUBBURI_PG_URL='jdbc:postgresql://127.0.0.1:25432/owned_ci',
            PUBBURI_PG_DB_NAME='owned_ci', PUBBURI_PG_USER='runtime', PUBBURI_PG_PASSWORD='runtime-secret',
            PUBBURI_PG_RUN_TOKEN='owned-fixture-token', PUBBURI_PG_CA='/tmp/public-ca.pem',
            PUBBURI_PG_CONTAINER_ID='a'*64, PUBBURI_PG_VALIDATION_LABEL='owned-fixture',
            PUBBURI_JPA_SOURCE_ID='12345678-1234-1234-1234-123456789abc', PUBBURI_JPA_REHEARSAL='1',
            PUBBURI_SERVER_PORT='15001', PUBBURI_FRONTEND_PORT='15002', PUBBURI_RECOMMENDATION_PORT='15003')

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
                         {'PUBBURI_FRONTEND_PORT':'15001'}, {'PUBBURI_CONTENT_FAMILY_BROWSE_DEMO_ENABLED':'yes'}, {'PUBBURI_RECOMMENDATION_BASE_URL':'https://remote.invalid'}, {'PUBBURI_SEARCH_ENDPOINT':'http://127.0.0.1:9200/private'}, {'PUBBURI_JPA_REHEARSAL':'0'}):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(RuntimeError):pg.configuration(self.config(Path(temporary), dict(self.env(), **mutation)))
        for text in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":1} true'):
            with self.assertRaises(RuntimeError):pg.strict_json(text)

    def test_inherited_credentials_and_jvm_injection_never_reach_base(self):
        with patch.dict(os.environ, {'PATH':'/tools', 'HOME':'/user', 'PUBBURI_PG_PASSWORD':'secret',
             'PUBBURI_DB_ROOT_PASSWORD':'root', 'PGPASSWORD':'control', 'SPRING_JPA_HIBERNATE_DDL_AUTO':'create',
             'JAVA_TOOL_OPTIONS':'-javaagent:secret', 'JDK_JAVA_OPTIONS':'-Dsecret=1'}, clear=True):
            self.assertEqual(pg.base_environment(), {'PATH':'/tools', 'HOME':'/user'})

    def test_three_children_receive_only_their_owned_runtime_settings(self):
        with patch.dict(os.environ, {'PATH':'/tools','PGPASSWORD':'control','PUBBURI_DB_ROOT_PASSWORD':'root',
                'PUBBURI_CONTENT_CATALOG_PATH':'inherited-private-file'}, clear=True):
            backend, frontend, recommendation = pg.child_environments(dict(self.env(),PUBBURI_CONTENT_FAMILY_BROWSE_DEMO_ENABLED='true'))
        self.assertEqual(backend['PUBBURI_PG_PASSWORD'],'runtime-secret')
        self.assertEqual(backend['PUBBURI_CONTENT_FAMILY_BROWSE_DEMO_ENABLED'],'true')
        self.assertEqual(backend['PUBBURI_JPA_PORT'],'15001')
        self.assertEqual(frontend,{'PATH':'/tools','PUBBURI_FRONTEND_PORT':'15002','VITE_API_PROXY_TARGET':'http://127.0.0.1:15001'})
        self.assertEqual(recommendation,{'PATH':'/tools','PUBBURI_RECOMMENDATION_PORT':'15003'})
        for env in (backend,frontend,recommendation):
            self.assertNotIn('PGPASSWORD',env);self.assertNotIn('PUBBURI_DB_ROOT_PASSWORD',env)
            self.assertNotIn('PUBBURI_CONTENT_CATALOG_PATH',env)

    def test_pg_destructive_commands_reject_before_env_or_docker(self):
        for name in ('init','install','down','reset-db'):
            with self.subTest(command=name), patch.object(local, 'environment') as environment, patch.object(pg, 'doctor') as doctor:
                args = argparse.Namespace(command=name, provider='postgresql', pg_config='secret', release_dir='private',
                    output_dir=None, backend_only=False, confirm_delete_local_data=False)
                with self.assertRaises(RuntimeError):pg.dispatch(local,args)
                environment.assert_not_called();doctor.assert_not_called()

    def test_existing_mysql_dispatch_still_requires_its_environment(self):
        with patch('sys.argv',['local_stack.py','doctor']), patch.object(local,'environment',return_value={'legacy':'yes'}) as env, patch.object(local,'doctor') as doctor:
            local.main()
            env.assert_called_once();doctor.assert_called_once_with({'legacy':'yes'})

    def test_pg_cli_routes_without_loading_mysql_env(self):
        with patch('sys.argv',['local_stack.py','doctor','--provider','postgresql','--pg-config','x','--release-dir','y']), patch.object(local,'environment') as env, patch.object(pg,'dispatch') as dispatch:
            local.main()
            env.assert_not_called();self.assertEqual(dispatch.call_args.args[1].provider,'postgresql')

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
            with zipfile.ZipFile(path,'w') as jar:
                for name in (entry,'BOOT-INF/lib/spring-data-jpa-fixture.jar','BOOT-INF/lib/hibernate-core-fixture.jar'):
                    jar.writestr(name,b'fixture')
            pg.validate_jar(path)
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
            with patch.object(local,'STATE',state), patch.object(pg,'package') as package, patch.object(pg,'start') as start:
                ci.local_release(env)
            package.assert_called_once();start.assert_called_once()
            config=pg.configuration(start.call_args.args[1].pg_config)
            self.assertEqual(set(config),pg.REQUIRED)
            self.assertNotIn('PUBBURI_JPA_CONTROL_PASSWORD',config)
            self.assertNotIn('PUBBURI_PG_MUTATING',config)
            self.assertTrue(start.call_args.args[1].backend_only)

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
                for name in ('BOOT-INF/classes/com/pubburi/rehearsal/JpaRehearsalApplication.class',
                             'BOOT-INF/lib/spring-data-jpa-fixture.jar','BOOT-INF/lib/hibernate-core-fixture.jar'):
                    archive.writestr(name,b'fixture')
            jar.chmod(0o600)
            value={'schema_version':1,'completed':True,'provider':'JPA_POSTGRESQL_REHEARSAL','source_commit':first,
                'source_files':pg.source_files(scope),'jar_sha256':pg.sha(jar),'java_feature':17,'postgresql_version':'18.6',
                'api_paths':37,'api_operations':44,'preparation':'database-independent-maven-verify'}
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
