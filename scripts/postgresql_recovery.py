#!/usr/bin/env python3
"""Explicit stopped-writer backup and fresh-target recovery of an owned local PostgreSQL copy."""
import argparse
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit

import local_stack
import postgresql_local as private
from postgresql_schema import verify_owned_postgres
from jpa_smoke import cleanup_on_termination

PG_KEYS = frozenset('PUBBURI_PG_' + key for key in (
    'URL', 'DB_NAME', 'USER', 'PASSWORD', 'RUN_TOKEN', 'CA', 'CONTAINER_ID', 'VALIDATION_LABEL'))
MAX_ARCHIVE = 128 * 1024 * 1024
PRIMARY = dict(line.split('\t') for line in (local_stack.BACKEND /
    'tools/postgresql-migration/src/main/resources/source-primary-contract.tsv').read_text().splitlines())
PRIMARY.update(t_comment_taste_tag='comment_id,tag', migration_source_receipt='singleton_id',
               flyway_schema_history='installed_rank')
SEQUENCES = ('t_product_id_seq', 't_order_o_id_seq', 't_order_detail_d_id_seq',
             't_comment_id_seq', 't_stamp_id_seq', 't_market_id_seq', 't_product_search_outbox_event_id_seq')
# Checked-in Flyway catalogs on pinned PostgreSQL18.6. Never adopt arbitrary source DDL.
KNOWN_SCHEMA_SHA256 = 'a60465c97ad299d75429372f0fd66f4dd7e88a57cbe22f580d1491ca656c1f71'
FEEDBACK_SCHEMA_SHA256 = '89b8239209751866014dcb687c85664628db1c92e9b36d1638972c8481334fdc'
FEEDBACK_PRIMARY = dict(PRIMARY, t_user_recommendation_feedback='user_id,product_id')
NOTEBOOK_SCHEMA_SHA256 = 'e0cd18858c5c971c0f1ab391707b895d384e58e08642949656338d98e3b46d95'
NOTEBOOK_PRIMARY = dict(FEEDBACK_PRIMARY, t_user_notebook='id')
CART_SCHEMA_SHA256 = '959bcbc3487b38d7f63477eb44165968a3200bf673e59707e1a9c36079103db1'
CART_PRIMARY = dict(NOTEBOOK_PRIMARY, t_user_cart='user_id',t_user_cart_item='user_id,product_id',t_user_cart_operation='user_id,operation_id')
NOTIFICATION_SCHEMA_SHA256 = '40b46b3abd58ab2bc34bff5c5b239238eaf68af3b866d6557dc3e1792dccb9b7'
NOTIFICATION_PRIMARY = dict(CART_PRIMARY, t_user_notification='id')


def known_check(row):
    # PG18 pg_dump/reparse distributes these versioned varchar[]->text[] casts.
    # Accept only the two exact known representations, not arbitrary SQL rewriting.
    variants=(('t_product_feature_tag','ck_feature_tag_kind','kind',('TASTE','AROMA')),
              ('t_user_preference','ck_preference_status','survey_status',('NOT_STARTED','SKIPPED','COMPLETED')),
              ('t_user_preference_value','ck_preference_value_kind','kind',('CATEGORY','TASTE','AROMA')),
              ('t_user_recommendation_feedback','ck_recommendation_feedback','feedback',('MATCHED','NOT_INTERESTED')),
              ('t_user_notebook','ck_notebook_experience','experience',('TRIED','INTERESTED')),
              ('t_user_cart_operation','ck_cart_operation_kind','kind',('MERGE','CHECKOUT')),
              ('t_user_cart_operation','ck_cart_operation_status','status',('APPLIED','RETIRED')))
    for table,name,column,values in variants:
        prefix='CHECK ('+column+'::text = ANY (ARRAY['
        original=prefix+', '.join("'"+v+"'::character varying" for v in values)+']::text[]))'
        restored=prefix+', '.join("'"+v+"'::character varying::text" for v in values)+']))'
        if row[:2]==(table,name) and row[2] in (original,restored):
            return (table,name,original,row[3])
    return row


def reject(code):
    raise ValueError('postgresql_recovery_' + code)


def configuration(path):
    value = private.strict_json(private.private_file(path, 65536))
    if (not isinstance(value, dict) or set(value) != {'schema_version', 'environment', 'writers_stopped'}
            or type(value['schema_version']) is not int or value['schema_version'] != 1
            or value['writers_stopped'] is not True or not isinstance(value['environment'], dict)
            or set(value['environment']) != PG_KEYS
            or any(not isinstance(v, str) or not v or '\x00' in v for v in value['environment'].values())):
        reject('explicit_private_config_required')
    env = value['environment']
    if (not re.fullmatch(r'[a-z][a-z0-9_]{0,50}', env['PUBBURI_PG_USER'])
            or not re.fullmatch(r'[A-Za-z0-9_-]{12,100}', env['PUBBURI_PG_RUN_TOKEN'])):
        reject('credentials_or_marker_rejected')
    verify_owned_postgres(env, child_env=private.base_environment())
    return env


def connect(env):
    import psycopg  # Same locked Psycopg3 environment as the local relay; never an app dependency.
    url = urlsplit(env['PUBBURI_PG_URL'][5:])
    return psycopg.connect(host=url.hostname, hostaddr=url.hostname, port=url.port,
        dbname=env['PUBBURI_PG_DB_NAME'], user=env['PUBBURI_PG_USER'], password=env['PUBBURI_PG_PASSWORD'],
        sslmode='verify-full', sslrootcert=env['PUBBURI_PG_CA'], gssencmode='disable',
        connect_timeout=3, tcp_user_timeout=10000, client_encoding='UTF8', autocommit=True,
        application_name='pubburi-stopped-recovery',
        options='-c search_path=pg_catalog -c timezone=UTC -c statement_timeout=60000 '
                '-c lock_timeout=3000 -c idle_in_transaction_session_timeout=180000')


def guard(connection, env):
    row = connection.execute("""SELECT current_database(), current_setting('server_version_num'),
        current_setting('server_encoding'), (SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()),
        (SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
           FROM pg_roles WHERE rolname=current_user),
        (SELECT count(*)=1 AND bool_and(token=%s) FROM public.pubburi_pg_migration_guard),
        EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user))
        """, (env['PUBBURI_PG_RUN_TOKEN'],)).fetchone()
    if row != (env['PUBBURI_PG_DB_NAME'], '180006', 'UTF8', True, False, True, False):
        reject('database_guard_rejected')
    # Other roles' backend_type/state can be NULL without pg_read_all_stats.
    # datid/pid stay public: reject every other backend on this DB, including
    # an already connected runtime whose role was subsequently made NOLOGIN.
    if connection.execute("SELECT count(*) FROM pg_stat_activity WHERE datid=(SELECT oid FROM pg_database WHERE datname=current_database()) AND pid<>pg_backend_pid()").fetchone()[0]:
        reject('other_clients_present')


def stopped_roles(connection):
    # Deliberately stronger than classifying current/inherited/SET-role privileges:
    # all other non-superuser logins that can CONNECT must be stopped, readers too.
    # Existing sessions are independently rejected by guard(); no roles are changed here.
    if connection.execute("""SELECT EXISTS(SELECT 1 FROM pg_roles r
        WHERE r.rolcanlogin AND NOT r.rolsuper AND r.rolname<>current_user
          AND has_database_privilege(r.oid,current_database(),'CONNECT'))""").fetchone()[0]:
        reject('writers_can_login')


def inventory(connection):
    relations = dict(connection.execute("SELECT c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pubburi' AND c.relkind IN ('r','S','v','m','f','p')").fetchall())
    if relations not in tuple(dict({name: 'r' for name in primary}, **{name: 'S' for name in SEQUENCES})
                              for primary in (PRIMARY, FEEDBACK_PRIMARY, NOTEBOOK_PRIMARY, CART_PRIMARY, NOTIFICATION_PRIMARY)):
        reject('schema_inventory_rejected')
    reject_unversioned_objects(connection)
    if connection.execute("""SELECT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='pubburi' AND (c.relowner<>(SELECT oid FROM pg_roles WHERE rolname=current_user) OR c.relrowsecurity))
        OR NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='pubburi' AND nspowner=(SELECT oid FROM pg_roles WHERE rolname=current_user))""").fetchone()[0]:
        reject('control_owner_required')
    shape = schema_state(connection)
    return shape, schema_primary(shape, 'versioned_schema_changed')


def reject_unversioned_objects(connection):
    if connection.execute("""SELECT
        EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('pubburi','public'))
        OR EXISTS(SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('pubburi','public') AND NOT t.tgisinternal)
        OR EXISTS(SELECT 1 FROM pg_rewrite r JOIN pg_class c ON c.oid=r.ev_class JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('pubburi','public'))
        OR EXISTS(SELECT 1 FROM pg_extension WHERE extname<>'plpgsql')
        OR EXISTS(SELECT 1 FROM pg_event_trigger)
        OR EXISTS(SELECT 1 FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('pubburi','public'))
        OR EXISTS(SELECT 1 FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_type t ON t.oid=a.atttypid JOIN pg_namespace tn ON tn.oid=t.typnamespace WHERE n.nspname='pubburi' AND a.attnum>0 AND NOT a.attisdropped AND tn.nspname<>'pg_catalog')
        OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('pubburi','public','pg_catalog','information_schema') AND nspname NOT LIKE 'pg_toast%' AND nspname NOT LIKE 'pg_temp_%')""").fetchone()[0]:
        reject('unversioned_executable_objects')


def schema_state(connection):
    relations = connection.execute("""SELECT c.relname,c.relkind,c.relpersistence,c.relispartition,c.relreplident,
        c.relforcerowsecurity,c.reloptions,COALESCE(t.spcname,''),COALESCE(a.amname,'')
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        LEFT JOIN pg_tablespace t ON t.oid=c.reltablespace LEFT JOIN pg_am a ON a.oid=c.relam
        WHERE n.nspname='pubburi' ORDER BY c.relname""").fetchall()
    triggers = connection.execute("""SELECT c.relname,k.conname,t.tgtype,t.tgenabled,t.tgdeferrable,t.tginitdeferred
        FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
        LEFT JOIN pg_constraint k ON k.oid=t.tgconstraint
        WHERE n.nspname='pubburi' ORDER BY c.relname,k.conname,t.tgtype""").fetchall()
    sequence_definitions = connection.execute("SELECT c.relname,s.seqtypid::regtype::text,s.seqstart,s.seqincrement,s.seqmin,s.seqmax,s.seqcache,s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pubburi' ORDER BY c.relname").fetchall()
    collations = connection.execute("SELECT collname,collprovider,collisdeterministic,colllocale,collversion,pg_collation_actual_version(c.oid) FROM pg_collation c JOIN pg_namespace n ON n.oid=c.collnamespace WHERE n.nspname='pubburi' ORDER BY collname").fetchall()
    columns = connection.execute("""SELECT c.relname,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,
        CASE WHEN a.attcollation=0 THEN NULL ELSE a.attcollation::regcollation::text END,
        a.attgenerated,pg_get_expr(d.adbin,d.adrelid,true)
        FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='pubburi' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped ORDER BY c.relname,a.attnum""").fetchall()
    constraints = connection.execute("""SELECT c.relname,k.conname,pg_get_constraintdef(k.oid,true),k.convalidated
        FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='pubburi' ORDER BY c.relname,k.conname""").fetchall()
    constraints = [known_check(row) for row in constraints]
    indexes = connection.execute("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='pubburi' ORDER BY tablename,indexname").fetchall()
    return {'columns':columns,'constraints':constraints,'indexes':indexes,'relations':relations,'triggers':triggers,
            'sequence_definitions':sequence_definitions,'collations':collations}


def schema_fingerprint(value):
    keys=('columns','constraints','indexes','sequence_definitions','collations','relations','triggers')
    return hashlib.sha256(json.dumps({key:value[key] for key in keys},ensure_ascii=False,
        sort_keys=True,separators=(',',':')).encode()).hexdigest()


def schema_primary(shape, error):
    try:
        fingerprint = schema_fingerprint(shape)
    except (KeyError, TypeError):
        reject(error)
    if fingerprint == KNOWN_SCHEMA_SHA256:
        return PRIMARY
    if fingerprint == FEEDBACK_SCHEMA_SHA256:
        return FEEDBACK_PRIMARY
    if fingerprint == NOTEBOOK_SCHEMA_SHA256:
        return NOTEBOOK_PRIMARY
    if fingerprint == CART_SCHEMA_SHA256:
        return CART_PRIMARY
    if fingerprint == NOTIFICATION_SCHEMA_SHA256:
        return NOTIFICATION_PRIMARY
    reject(error)


def state(connection):
    shape, primary_columns = inventory(connection)
    tables = {}
    for name, primary in sorted(primary_columns.items()):
        # Binary COPY preserves float bits, NULL, timestamps, text and json's original bytes.
        # Text C ordering is explicit, independent of the business ICU collation.
        order = ','.join('"' + key + '"::text COLLATE "C"' for key in primary.split(','))
        digest = hashlib.sha256()
        with connection.cursor().copy('COPY (SELECT * FROM pubburi."' + name + '" ORDER BY ' + order + ') TO STDOUT (FORMAT binary)') as copy:
            for chunk in copy:
                digest.update(chunk)
        tables[name] = {'rows': connection.execute('SELECT count(*) FROM pubburi."' + name + '"').fetchone()[0],
                        'sha256': digest.hexdigest()}
    sequences = {name: list(connection.execute('SELECT last_value,is_called FROM pubburi."' + name + '"').fetchone()) for name in SEQUENCES}
    receipt = connection.execute('SELECT source_id::text FROM pubburi.migration_source_receipt WHERE singleton_id=1').fetchall()
    target = connection.execute('SELECT source_id::text FROM pubburi.t_product_search_relay_target WHERE singleton_id=1').fetchall()
    if len(receipt) != 1 or target != receipt:
        reject('source_binding_rejected')
    return dict(shape,tables=tables,sequences=sequences,source_id=receipt[0][0])


def client(env, binary, arguments, *, input=None, output=None, listing=False, error_file=None):
    verify_owned_postgres(env, child_env=private.base_environment())
    base = dict(private.base_environment(), PGPASSWORD=env['PUBBURI_PG_PASSWORD'], PGSSLMODE='verify-full',
        PGSSLROOTCERT='/tmp/pubburi-pg-ci-tls/ca.pem', PGGSSENCMODE='disable', PGCONNECT_TIMEOUT='3',
        PGOPTIONS='-c statement_timeout=60000 -c lock_timeout=3000')
    cid = env['PUBBURI_PG_CONTAINER_ID']
    ca = subprocess.run(['docker', 'exec', cid, 'cat', base['PGSSLROOTCERT']], env=private.base_environment(),
                        capture_output=True, check=True, timeout=5).stdout
    if ca != Path(env['PUBBURI_PG_CA']).read_bytes():
        reject('client_ca_mismatch')
    version = subprocess.run(['docker', 'exec', cid, binary, '--version'], env=private.base_environment(),
                            capture_output=True, check=True, timeout=5).stdout.decode()
    if not re.fullmatch(binary + r' \(PostgreSQL\) 18\.6(?: \([^\n]+\))?\n', version):
        reject('client_version_rejected')
    command = ['docker', 'exec', '-i']
    for key in ('PGPASSWORD', 'PGSSLMODE', 'PGSSLROOTCERT', 'PGGSSENCMODE', 'PGCONNECT_TIMEOUT', 'PGOPTIONS'):
        command += ['-e', key]
    command += [cid, binary]
    if not listing:
        command += ['--no-password', '--host=127.0.0.1', '--username=' + env['PUBBURI_PG_USER'],
                    '--dbname=' + env['PUBBURI_PG_DB_NAME']]
    command += arguments
    # Only fixed arguments; no shell, inherited credentials, raw stderr or archive contents on stdout.
    try:
        result=subprocess.run(command, env=base, input=input,
            stdout=subprocess.PIPE if listing else output if output is not None else subprocess.DEVNULL,
            stderr=subprocess.PIPE, check=True, timeout=120)
    except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
        if error_file is not None:
            with Path(error_file).open('xb') as log:
                Path(error_file).chmod(0o600)
                log.write((error.stderr or b'client timed out; completion unknown')[:65536])
        raise
    if result.stderr:
        reject('client_diagnostics_require_review')
    return result.stdout


def expected_toc(state):
    expected={'SCHEMA - pubburi','COLLATION pubburi pubburi_ci'}
    for kind in ('TABLE','TABLE DATA'):
        expected.update(kind+' pubburi '+name for name in schema_primary(state, 'archive_schema_changed'))
    for kind in ('SEQUENCE','SEQUENCE SET'):
        expected.update(kind+' pubburi '+name for name in SEQUENCES)
    for table,name,definition,_ in state['constraints']:
        if definition.startswith(('PRIMARY KEY','UNIQUE','EXCLUDE','FOREIGN KEY')):
            kind='FK CONSTRAINT' if definition.startswith('FOREIGN KEY') else 'CONSTRAINT'
            expected.add(kind+' pubburi '+table+' '+name)
    constraint_indexes={row[1] for row in state['constraints'] if row[2].startswith(('PRIMARY KEY','UNIQUE','EXCLUDE'))}
    expected.update('INDEX pubburi '+row[1] for row in state['indexes'] if row[1] not in constraint_indexes)
    return expected


def archive_inventory(env, archive, state):
    listing = client(env, 'pg_restore', ['--list'], input=archive, listing=True).decode('utf-8')
    expected=expected_toc(state)
    observed=set()
    for line in listing.splitlines():
        if not line or line.startswith(';'):
            continue
        match = re.fullmatch(r'[0-9]+; [0-9]+ [0-9]+ (.+) [a-z][a-z0-9_]{0,50}', line)
        if not match:
            reject('archive_toc_rejected')
        item = match[1]
        if item not in expected or item in observed:
            reject('archive_executable_unknown_or_duplicate_object')
        observed.add(item)
    if observed != expected:
        reject('archive_incomplete')


def lock_business_tables(connection, primary):
    connection.execute('LOCK TABLE ' + ','.join('pubburi."' + name + '"' for name in sorted(primary)) + ' IN SHARE MODE')


def backup(env, destination, *, local=local_stack):
    directory = private.private_directory(destination, local, create=True)
    with connect(env) as connection:
        guard(connection, env)
        stopped_roles(connection)
        connection.execute('BEGIN ISOLATION LEVEL REPEATABLE READ')
        _, primary = inventory(connection)
        lock_business_tables(connection, primary)
        before = state(connection)
        snapshot = connection.execute('SELECT pg_export_snapshot()').fetchone()[0]
        if not re.fullmatch(r'[0-9A-F]+-[0-9A-F]+-[0-9]+', snapshot):
            reject('snapshot_rejected')
        archive = directory / 'database.dump'
        with archive.open('xb') as output:
            archive.chmod(0o600)
            client(env, 'pg_dump', ['--format=custom', '--schema=pubburi', '--no-owner', '--no-acl',
                   '--snapshot=' + snapshot], output=output,error_file=directory/'backup-error.log')
        if archive.stat().st_size > MAX_ARCHIVE:
            reject('archive_limit')
        archive_inventory(env, private.private_file(archive, MAX_ARCHIVE), before)
        guard(connection, env)
        stopped_roles(connection)
        if state(connection) != before:
            reject('source_changed')
        connection.rollback()  # Release our read/SHARE locks without any source data writes.
    private.write_json(directory / 'manifest.json', {'schema_version': 1, 'kind': 'stopped-pg18.6-v1',
        'archive_sha256': private.sha(archive), 'archive_bytes': archive.stat().st_size,
        'source_database': env['PUBBURI_PG_DB_NAME'], 'state': before})
    return private.sha(directory / 'manifest.json')


def approved_archive(directory, expected, local=local_stack):
    directory = private.private_directory(directory, local)
    raw = private.private_file(directory / 'manifest.json', 2 * 1024 * 1024)
    if not re.fullmatch(r'[0-9a-f]{64}', expected or '') or hashlib.sha256(raw).hexdigest() != expected:
        reject('manifest_approval_required')
    manifest = private.strict_json(raw)
    if (not isinstance(manifest, dict) or set(manifest) != {'schema_version','kind','archive_sha256','archive_bytes','source_database','state'}
            or type(manifest['schema_version']) is not int or manifest['schema_version'] != 1
            or manifest['kind'] != 'stopped-pg18.6-v1' or not isinstance(manifest['state'],dict)
            or not isinstance(manifest['archive_sha256'],str) or not re.fullmatch(r'[0-9a-f]{64}',manifest['archive_sha256'])):
        reject('manifest_rejected')
    schema_primary(manifest['state'], 'manifest_schema_changed')
    archive = private.private_file(directory / 'database.dump', MAX_ARCHIVE)
    if len(archive) != manifest['archive_bytes'] or hashlib.sha256(archive).hexdigest() != manifest['archive_sha256']:
        reject('archive_changed')
    return manifest, archive


def restore(env, archive_directory, expected, destination, *, local=local_stack):
    manifest, archive = approved_archive(archive_directory, expected, local)
    if manifest['source_database'] == env['PUBBURI_PG_DB_NAME']:
        reject('distinct_fresh_target_required')
    directory = private.private_directory(destination, local, create=True)
    with connect(env) as connection:
        guard(connection, env)
        stopped_roles(connection)
        reject_unversioned_objects(connection)
        if connection.execute("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('pg_catalog','information_schema','public') AND nspname NOT LIKE 'pg_toast%' AND nspname NOT LIKE 'pg_temp_%') OR EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname NOT IN ('pubburi_pg_migration_guard','pubburi_pg_migration_guard_pkey'))").fetchone()[0]:
            reject('empty_marker_only_target_required')
        connection.close()  # pg_restore must be the only client on the target.
    archive_inventory(env, archive, manifest['state'])
    private.write_json(directory / 'attempt.json', {'status': 'restore_started_completion_unknown',
        'manifest_sha256': expected, 'target_database': env['PUBBURI_PG_DB_NAME']})
    # The dump and exact TOC already select only pubburi. pg_restore -n would
    # exclude the SCHEMA entry itself, so a genuinely empty target could not restore.
    client(env, 'pg_restore', ['--single-transaction', '--exit-on-error', '--no-owner', '--no-acl'],
           input=archive,error_file=directory/'restore-error.log')
    with connect(env) as connection:
        guard(connection, env)
        stopped_roles(connection)
        connection.execute('BEGIN ISOLATION LEVEL REPEATABLE READ')
        actual = state(connection)
        if json.loads(json.dumps(actual)) != manifest['state']:
            reject('restored_state_mismatch')
        connection.rollback()
    private.write_json(directory / 'promotion.json', promotion_receipt(env, expected, actual['source_id']))
    private.write_json(directory / 'completed.json', {'status': 'verified', 'manifest_sha256': expected,
        'source_id': actual['source_id'], 'tables': len(actual['tables']), 'sequences': len(actual['sequences'])})


def database_identity(env):
    return {key: env[key] for key in private.DATABASE_IDENTITY if key != 'PUBBURI_JPA_SOURCE_ID'}


def promotion_receipt(env, manifest_pin, source_id):
    target = hashlib.sha256(json.dumps(database_identity(env), sort_keys=True).encode()).hexdigest()
    return {'schema_version': 1, 'manifest_sha256': manifest_pin, 'source_id': source_id, 'target_sha256': target}


def promotion_fence(connection, env, *, source):
    guard(connection, env)
    if connection.execute('SELECT EXISTS(SELECT 1 FROM pg_prepared_xacts WHERE database=current_database())').fetchone()[0]:
        reject('prepared_transactions_present')
    if not source:
        stopped_roles(connection)
    elif connection.execute("""SELECT EXISTS(SELECT 1 FROM pg_roles r
        WHERE NOT r.rolsuper AND r.rolname<>current_user
          AND has_database_privilege(r.oid,current_database(),'CONNECT'))""").fetchone()[0]:
        # Source remains fenced even when a shared runtime role is opened on the target.
        reject('selected_source_connect_granted')


def promote(local, source, target, archive_dir, manifest_pin, restore_dir, completion_pin, pg_config, release_dir):
    with local.operation_lock():
        previous = private.selection_record(local)
        if previous is None:
            reject('selected_source_required')
        selected = private.configuration(previous['pg_config'], previous['config_sha256'])
        directory = Path(release_dir)
        pins = private.selection_pins(Path(pg_config), directory)
        runtime = private.configuration(pg_config, pins['config_sha256'])
        directory, _ = private.release(directory, local)
        manifest, _ = approved_archive(archive_dir, manifest_pin, local)
        state_at_backup = manifest['state']
        primary = schema_primary(state_at_backup, 'manifest_schema_changed')
        source_id = state_at_backup['source_id']
        if (database_identity(source) != database_identity(selected)
                or database_identity(target) != database_identity(runtime)
                or source['PUBBURI_PG_DB_NAME'] != manifest['source_database']
                or source['PUBBURI_PG_DB_NAME'] == target['PUBBURI_PG_DB_NAME']
                or source_id != selected['PUBBURI_JPA_SOURCE_ID'] or source_id != runtime['PUBBURI_JPA_SOURCE_ID']
                or source['PUBBURI_PG_USER'] == selected['PUBBURI_PG_USER']
                or target['PUBBURI_PG_USER'] == runtime['PUBBURI_PG_USER']):
            reject('promotion_identity_mismatch')
        restored = private.private_directory(restore_dir, local)
        completion = private.strict_json(private.private_file(restored / 'completed.json', 8192))
        raw = private.private_file(restored / 'promotion.json', 8192)
        receipt = private.strict_json(raw)
        if (not re.fullmatch(r'[0-9a-f]{64}', completion_pin or '')
                or hashlib.sha256(raw).hexdigest() != completion_pin
                or not isinstance(receipt, dict) or type(receipt.get('schema_version')) is not int
                or receipt != promotion_receipt(target, manifest_pin, source_id)
                or completion != {'status': 'verified', 'manifest_sha256': manifest_pin, 'source_id': source_id,
                    'tables': len(primary), 'sequences': len(SEQUENCES)}):
            reject('promotion_completion_mismatch')
        for env in (source, target, runtime):
            verify_owned_postgres(env, child_env=private.base_environment())

        with ExitStack() as stack:
            connections = []
            for env, is_source in ((source, True), (target, False)):
                connection = connect(env)
                stack.callback(connection.close)
                promotion_fence(connection, env, source=is_source)
                connection.execute('BEGIN ISOLATION LEVEL READ COMMITTED')
                lock_business_tables(connection, primary)
                if json.loads(json.dumps(state(connection))) != state_at_backup:
                    reject('selected_source_changed' if is_source else 'restored_target_changed')
                connections.append((connection, env, is_source))
            target_connection = connections[-1][0]
            if target_connection.execute("""SELECT NOT (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole
                OR rolreplication OR rolbypassrls)
                AND has_database_privilege(oid,current_database(),'CONNECT') FROM pg_roles WHERE rolname=%s
                """, (runtime['PUBBURI_PG_USER'],)).fetchone() != (True,):
                reject('promotion_target_runtime_not_stopped_or_missing')
            if private.selection_record(local) != previous:
                reject('selected_source_changed')
            private.configuration(previous['pg_config'], previous['config_sha256'])
            for connection, env, is_source in connections:
                promotion_fence(connection, env, source=is_source)
            # The external CONNECT fence and both table locks cover publication.
            private.publish_selection(local, pg_config, directory, pins, announce=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('backup','restore','promote'))
    parser.add_argument('--config', required=True)
    parser.add_argument('--output-dir')
    parser.add_argument('--archive-dir')
    parser.add_argument('--expected-sha256')
    parser.add_argument('--target-config')
    parser.add_argument('--restore-dir')
    parser.add_argument('--expected-restore-sha256')
    parser.add_argument('--pg-config')
    parser.add_argument('--release-dir')
    args = parser.parse_args()
    try:
        promotion_args = (args.target_config, args.restore_dir, args.expected_restore_sha256,
                          args.pg_config, args.release_dir)
        with cleanup_on_termination():
            if args.command == 'promote':
                if args.output_dir or not all((*promotion_args, args.archive_dir, args.expected_sha256)):
                    reject('explicit_promotion_arguments_required')
                promote(local_stack, configuration(args.config), configuration(args.target_config),
                    args.archive_dir, args.expected_sha256, args.restore_dir,
                    args.expected_restore_sha256, args.pg_config, args.release_dir)
                result = {'status': 'selected', 'provider': 'postgresql', 'source_fenced': True, 'target_runtime': 'stopped'}
            else:
                if any(promotion_args) or not args.output_dir:
                    reject('unexpected_recovery_arguments')
                with local_stack.operation_lock():
                    env = configuration(args.config)
                    if args.command == 'backup':
                        if args.archive_dir or args.expected_sha256:
                            reject('unexpected_backup_arguments')
                        result = {'status':'verified','manifest_sha256':backup(env,args.output_dir)}
                    else:
                        if not args.archive_dir or not args.expected_sha256:
                            reject('explicit_archive_required')
                        restore(env,args.archive_dir,args.expected_sha256,args.output_dir)
                        result = {'status':'verified'}
        print(json.dumps(result))
        return 0
    except Exception:
        # Neither DB errors/credentials nor source rows are public diagnostics. Keep the
        # archive and started/partial target for operator inspection; never retry/reset it.
        print(json.dumps({'status':'failed','error_code':'postgresql_recovery_rejected_or_completion_unknown'}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
