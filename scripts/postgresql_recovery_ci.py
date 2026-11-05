"""Recovery acceptance inside the existing owned source/JPA CI lifecycle, never an independent stack."""
from contextlib import contextmanager
import http.cookiejar
import json
from pathlib import Path
import re
import secrets
import subprocess
import time

import local_stack
import postgresql_recovery as recovery
from jpa_smoke import child_environment, request_json, verify_jar, verify_openapi_scope, cleanup_on_termination, defer_termination
from postgresql_schema import verify_owned_postgres


@contextmanager
def server(env, directory, name):
    verify_owned_postgres(env)
    jar=directory/'runtime.jar'
    if not jar.exists():
        jar.write_bytes((local_stack.BACKEND/'tools/jpa-rehearsal/target/pubburi-jpa-rehearsal-0.0.1-SNAPSHOT.jar').read_bytes())
        jar.chmod(0o600)
    verify_jar(jar)
    log=directory/(name+'.log')
    child=dict(child_environment(env),PUBBURI_JPA_PORT='0',PUBBURI_RECOMMENDATION_BASE_URL='http://127.0.0.1:1')
    java=str(Path(child['JAVA_HOME'])/'bin/java') if child.get('JAVA_HOME') else 'java'
    with cleanup_on_termination(),log.open('x') as output:
        log.chmod(0o600)
        process=None
        try:
            with defer_termination():
                process=subprocess.Popen([java,'-jar',str(jar)],cwd=local_stack.BACKEND,env=child,
                    stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
            end=time.monotonic()+45
            while time.monotonic()<end:
                if process.poll() is not None:raise ValueError('recovery_ci_jpa_start_failed')
                match=re.search(r'Tomcat started on port (\d+)',log.read_text(errors='replace'))
                if match:
                    base='http://127.0.0.1:'+match[1]
                    status,docs=request_json(http.cookiejar.CookieJar(),base,'GET','/v3/api-docs',None,time.monotonic()+10)
                    if status!=200:raise ValueError('recovery_ci_openapi_failed')
                    verify_openapi_scope(docs)
                    yield base
                    break
                time.sleep(.1)
            else:raise ValueError('recovery_ci_start_timeout')
        finally:
            if process is not None:local_stack.stop(process)


def run(environ, runtime, recovery_control):
    from jpa_rehearsal_ci import runtime_grants
    if environ.get('PUBBURI_JPA_CI')!='1':raise ValueError('recovery_ci_explicit_fixture_required')
    directory=local_stack.STATE/('worker-recovery-'+secrets.token_hex(16));directory.mkdir(mode=0o700)
    control=dict(runtime,PUBBURI_PG_USER=runtime['PUBBURI_JPA_CONTROL_USER'],PUBBURI_PG_PASSWORD=runtime['PUBBURI_JPA_CONTROL_PASSWORD'])
    target=dict(control,PUBBURI_PG_DB_NAME=environ['PUBBURI_PG_RESTORE_DB_NAME'],
        PUBBURI_PG_RUN_TOKEN=environ['PUBBURI_PG_RESTORE_RUN_TOKEN'],
        PUBBURI_PG_URL=runtime['PUBBURI_PG_URL'].rsplit('/',1)[0]+'/'+environ['PUBBURI_PG_RESTORE_DB_NAME'])
    if not isinstance(recovery_control,dict) or set(recovery_control)!={'user','password'}:
        raise ValueError('recovery_ci_separate_control_required')
    admin=dict(control,PUBBURI_PG_USER=recovery_control['user'],PUBBURI_PG_PASSWORD=recovery_control['password'])
    verify_owned_postgres(control);verify_owned_postgres(target)
    role=runtime['PUBBURI_PG_USER']
    if not re.fullmatch('[a-z][a-z0-9_]{0,50}',role):raise ValueError('recovery_ci_runtime_role_invalid')
    actor='ci_jpa_recovery_'+secrets.token_hex(8);password=secrets.token_hex(20);new_password=secrets.token_hex(20)
    def request(c,base,method,path,body=None,expected=200):
        status,body=request_json(c,base,method,path,body,time.monotonic()+12)
        if status!=expected:raise ValueError('recovery_ci_http_contract_failed')
        return body['data']
    with server(runtime,directory,'before') as base:
        admin_cookie=http.cookiejar.CookieJar();user=http.cookiejar.CookieJar()
        request(admin_cookie,base,'POST','/api/auth/login',{'id':runtime['PUBBURI_JPA_ADMIN_ID'],'pass':runtime['PUBBURI_JPA_ADMIN_PASSWORD']})
        request(user,base,'POST','/api/users',{'id':actor,'name':'Recovery authored member','pass':password},201)
        request(user,base,'POST','/api/auth/login',{'id':actor,'pass':password})
        product=request(admin_cookie,base,'POST','/api/admin/products',{'name':'Recovery 원문 🌾','type':'증류주','price':2300,'img':'authored.png','abv':15.5,'orderCount':0},201)
        pid=product['id']
        review=request(user,base,'POST','/api/comments',{'productId':pid,'rating':3.75,'comment':'Recovery 후기\n🌾','tasteTags':['dry,fruit\none','🌾']},201)
        order=request(user,base,'POST','/api/orders',{'details':[{'productId':pid,'quantity':3}]},201)
        request(user,base,'PUT','/api/users/me/favorites/'+str(pid))
        request(user,base,'PUT','/api/users/me/password',{'currentPassword':password,'newPassword':new_password})
        changed={key:product[key] for key in ('name','type','price','img','abv','orderCount')}
        changed.update(name='Recovery changed price',price=9999)
        request(admin_cookie,base,'PUT','/api/admin/products/'+str(pid),changed)
        retained_order=request(user,base,'GET','/api/orders/'+str(order['oId']))
        if retained_order['totalPrice']!=6900:raise ValueError('recovery_ci_order_snapshot_changed')
        # Existing DB strings are not new API input: preserve case/trailing space and
        # 40 supplementary code points (80 UTF-16 units) without API normalization.
        with recovery.connect(control) as connection:
            for raw_tag in ('UPPER Trail ','🌾'*40):
                connection.execute('INSERT INTO pubburi.t_comment_taste_tag(comment_id,tag) VALUES (%s,%s)',(review['id'],raw_tag))
        retained_reviews=request(user,base,'GET','/api/products/'+str(pid)+'/comments?includeTasteTags=true&includeSummary=true')
    # These are explicit authored control fixtures, not claims that a real relay ACK happened.
    with recovery.connect(control) as connection:
        event=connection.execute('SELECT max(event_id) FROM pubburi.t_product_search_outbox WHERE product_id=%s',(pid,)).fetchone()[0]
        first=connection.execute('SELECT min(event_id) FROM pubburi.t_product_search_outbox WHERE product_id=%s',(pid,)).fetchone()[0]
        if first==event:raise ValueError('recovery_ci_distinct_delivery_states_required')
        connection.execute("UPDATE pubburi.t_product_search_delivery SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,attempts=1 WHERE event_id=%s",(first,))
        claim='12345678-1234-1234-1234-123456789abc'
        connection.execute("UPDATE pubburi.t_product_search_delivery SET status='IN_FLIGHT',claim_token=%s::uuid,lease_until=CURRENT_TIMESTAMP+interval '1 hour',attempts=1 WHERE event_id=%s",(claim,event))
        connection.execute('BEGIN')
        for sequence in recovery.SEQUENCES:connection.execute("SELECT nextval('pubburi."+sequence+"')")
        connection.rollback()  # Consumed sequences deliberately survive an aborted write.
    # Gates must reject a live writer and an otherwise idle, still-login-capable runtime.
    archive=local_stack.STATE/('pg-backup-'+secrets.token_hex(16))
    with recovery.connect(control) as connection:
        try:recovery.backup(control,local_stack.STATE/('pg-refused-'+secrets.token_hex(16)))
        except ValueError as error:
            if 'other_clients_present' not in str(error):raise
        else:raise ValueError('recovery_ci_live_client_accepted')
    try:recovery.backup(control,local_stack.STATE/('pg-refused-'+secrets.token_hex(16)))
    except ValueError as error:
        if 'writers_can_login' not in str(error):raise
    else:raise ValueError('recovery_ci_live_role_accepted')
    # NOLOGIN prevents new authentication, not writes by a pre-existing runtime
    # session. Its backend_type is hidden from this non-superuser control role.
    with recovery.connect(runtime) as held_runtime:
        with recovery.connect(admin) as connection:connection.execute('ALTER ROLE '+role+' NOLOGIN')
        try:
            with recovery.connect(control) as connection:
                hidden=connection.execute('SELECT backend_type FROM pg_stat_activity WHERE pid=%s',
                    (held_runtime.info.backend_pid,)).fetchone()
                if hidden!=(None,):raise ValueError('recovery_ci_cross_role_stats_boundary_changed')
            refused=local_stack.STATE/('pg-refused-'+secrets.token_hex(16))
            try:recovery.backup(control,refused)
            except ValueError as error:
                if 'other_clients_present' not in str(error):raise
            else:raise ValueError('recovery_ci_existing_nologin_runtime_accepted')
            if (refused/'manifest.json').exists():raise ValueError('recovery_ci_unsafe_backup_completed')
        finally:
            with recovery.connect(admin) as connection:connection.execute('ALTER ROLE '+role+' LOGIN')
    with recovery.connect(admin) as connection:connection.execute('ALTER ROLE '+role+' NOLOGIN')
    try:
        # An injected executable object is rejected without emitting a usable archive.
        with recovery.connect(control) as connection:connection.execute("CREATE FUNCTION pubburi.recovery_forbidden() RETURNS int LANGUAGE sql AS 'SELECT 1'")
        try:
            try:recovery.backup(control,local_stack.STATE/('pg-refused-'+secrets.token_hex(16)))
            except ValueError as error:
                if 'unversioned_executable_objects' not in str(error):raise
            else:raise ValueError('recovery_ci_executable_object_accepted')
        finally:
            with recovery.connect(control) as connection:connection.execute('DROP FUNCTION pubburi.recovery_forbidden()')
        # The catalog pin catches ordinary DDL drift as well as executable objects.
        with recovery.connect(control) as connection:connection.execute('ALTER TABLE pubburi.t_comment ALTER rating SET DEFAULT 2')
        try:
            try:recovery.backup(control,local_stack.STATE/('pg-refused-'+secrets.token_hex(16)))
            except ValueError as error:
                if 'versioned_schema_changed' not in str(error):raise
            else:raise ValueError('recovery_ci_default_drift_accepted')
        finally:
            with recovery.connect(control) as connection:connection.execute('ALTER TABLE pubburi.t_comment ALTER rating SET DEFAULT 1')
        pin=recovery.backup(control,archive)
        manifest=json.loads((archive/'manifest.json').read_text())
        # A wrong pin is rejected before target schema/sequence creation.
        try:recovery.restore(target,archive,'0'*64,local_stack.STATE/('pg-refused-'+secrets.token_hex(16)))
        except ValueError:pass
        else:raise ValueError('recovery_ci_unapproved_archive_accepted')
        with recovery.connect(target) as connection:
            if connection.execute("SELECT to_regnamespace('pubburi')").fetchone()[0] is not None:raise ValueError('recovery_ci_early_target_write')
        # A marker-only target with an extra function is not accepted as fresh.
        admin_target=dict(admin,**{key:target[key] for key in ('PUBBURI_PG_URL','PUBBURI_PG_DB_NAME')})
        with recovery.connect(admin_target) as connection:connection.execute("CREATE FUNCTION public.recovery_forbidden() RETURNS int LANGUAGE sql AS 'SELECT 1'")
        try:
            try:recovery.restore(target,archive,pin,local_stack.STATE/('pg-refused-'+secrets.token_hex(16)))
            except ValueError as error:
                if 'unversioned_executable_objects' not in str(error):raise
            else:raise ValueError('recovery_ci_target_function_accepted')
        finally:
            with recovery.connect(admin_target) as connection:connection.execute('DROP FUNCTION public.recovery_forbidden()')
        attempt=local_stack.STATE/('pg-restored-'+secrets.token_hex(16))
        recovery.restore(target,archive,pin,attempt)
        # Source remains precisely frozen, including raw bytes, epoch, claim/ACK and sequence gaps.
        with recovery.connect(control) as connection:
            if json.loads(json.dumps(recovery.state(connection)))!=manifest['state']:raise ValueError('recovery_ci_source_mutated')
        with recovery.connect(target) as connection:connection.execute(runtime_grants(role))
        # The destination has a different marker; preserve it and grant only its read access.
        url=target['PUBBURI_PG_URL'];admin_target=dict(admin,PUBBURI_PG_URL=url,PUBBURI_PG_DB_NAME=target['PUBBURI_PG_DB_NAME'])
        with recovery.connect(admin_target) as connection:connection.execute('GRANT SELECT ON public.pubburi_pg_migration_guard TO '+role)
    finally:
        with recovery.connect(admin) as connection:connection.execute('ALTER ROLE '+role+' LOGIN')
    restored=dict(runtime,**{key:target[key] for key in ('PUBBURI_PG_URL','PUBBURI_PG_DB_NAME','PUBBURI_PG_RUN_TOKEN')})
    with server(restored,directory,'after') as base:
        user=http.cookiejar.CookieJar()
        request(user,base,'POST','/api/auth/login',{'id':actor,'pass':password},expected=401)
        request(user,base,'POST','/api/auth/login',{'id':actor,'pass':new_password})
        if request(user,base,'GET','/api/orders/'+str(order['oId']))!=retained_order:raise ValueError('recovery_ci_order_not_restored')
        if request(user,base,'GET','/api/products/'+str(pid)+'/comments?includeTasteTags=true&includeSummary=true')!=retained_reviews:raise ValueError('recovery_ci_reviews_not_restored')
        if not request(user,base,'GET','/api/users/me/favorites/'+str(pid))['favorited']:raise ValueError('recovery_ci_favorite_not_restored')
        created=request(user,base,'POST','/api/comments',{'productId':pid,'rating':2.5,'comment':'After recovery','tasteTags':[]},201)
        value,called=manifest['state']['sequences']['t_comment_id_seq']
        if created['id']!=(value+1 if called else value):raise ValueError('recovery_ci_consumed_id_reused')
    report={'status':'passed','business_tables':17,'control_tables':2,'sequences':7,
        'post_import_order_review_favorite_password_preserved':True,'schema_and_binary_cells_exact_before_restart':True,
        'archive_manifest_sha256':pin,'artifact_directory':str(archive.relative_to(local_stack.BACKEND)),
        'attempt_directory':str(attempt.relative_to(local_stack.BACKEND)),
        'source_database':control['PUBBURI_PG_DB_NAME'],'restored_database':target['PUBBURI_PG_DB_NAME'],
        'after_start_writes':'one new review; complete frozen equality no longer claimed after new write'}
    recovery.private.write_json(directory/'result.json',report)
    # Private control/runtime material can be retained by the owning caller for independent UI acceptance.
    minimal={key:restored[key] for key in recovery.PG_KEYS}
    minimal.update(PUBBURI_JPA_REHEARSAL='1',PUBBURI_JPA_SOURCE_ID=restored['PUBBURI_JPA_SOURCE_ID'],PUBBURI_JPA_PORT='0')
    recovery.private.write_json(directory/'fixture.json',{'environment':minimal,'control':{key:target[key] for key in recovery.PG_KEYS},'actor':{'id':actor,'password':new_password},
        'product_id':pid,'order_id':order['oId'],'review_id':review['id'],'archive':str(archive),'manifest_sha256':pin,
        'post_restore_writes':{'review_id':created['id'],'comment_sequence_before':[value,called]},
        'admin_actor':{'id':runtime['PUBBURI_JPA_ADMIN_ID'],'password':runtime['PUBBURI_JPA_ADMIN_PASSWORD']}})
    print('Owned stopped-writer PostgreSQL recovery, exact state and JPA restart passed; no default cutover.',flush=True)
    return directory
