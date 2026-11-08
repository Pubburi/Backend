"""Recovery's operator approval, private artifact and explicit failure boundaries."""
import hashlib
import json
import tempfile
from pathlib import Path
from unittest import TestCase
from unittest.mock import patch, Mock

import local_stack
import postgresql_recovery as recovery


class PostgreSQLRecoveryTests(TestCase):
    def env(self):
        return dict(PUBBURI_PG_URL='jdbc:postgresql://127.0.0.1:25432/source_ci',
            PUBBURI_PG_DB_NAME='source_ci',PUBBURI_PG_USER='recovery_control',PUBBURI_PG_PASSWORD='authored-secret',
            PUBBURI_PG_RUN_TOKEN='authored-owned-marker',PUBBURI_PG_CA='/tmp/authored-ca.pem',
            PUBBURI_PG_CONTAINER_ID='a'*64,PUBBURI_PG_VALIDATION_LABEL='authored-owned')

    def test_config_requires_stopped_writers_and_exact_private_projection(self):
        with tempfile.TemporaryDirectory() as root:
            file=Path(root)/'config.json'
            value={'schema_version':1,'environment':self.env(),'writers_stopped':True}
            file.write_text(json.dumps(value));file.chmod(0o600)
            with patch.object(recovery,'verify_owned_postgres') as guard:
                self.assertEqual(recovery.configuration(file),self.env())
                guard.assert_called_once()
            for mutation in ({'writers_stopped':False},{'schema_version':True},{'admin_password':'bad'},
                             {'environment':dict(self.env(),PGPASSWORD='bad')}):
                file.write_text(json.dumps(dict(value,**mutation)))
                with patch.object(recovery,'verify_owned_postgres') as guard:
                    with self.assertRaises((ValueError,RuntimeError)):recovery.configuration(file)
                    guard.assert_not_called()
            file.write_text(json.dumps(value));file.chmod(0o644)
            with self.assertRaises(RuntimeError):recovery.configuration(file)

    def test_every_other_database_backend_is_rejected_using_only_public_stats_fields(self):
        connection=Mock()
        valid=(self.env()['PUBBURI_PG_DB_NAME'],'180006','UTF8',True,False,True,False)
        first=Mock();first.fetchone.return_value=valid
        for other_backends in (0,1):
            second=Mock();second.fetchone.return_value=(other_backends,)
            connection.execute.side_effect=[first,second]
            if other_backends:
                with self.assertRaisesRegex(ValueError,'other_clients_present'):recovery.guard(connection,self.env())
            else:recovery.guard(connection,self.env())
            query=connection.execute.call_args.args[0]
            self.assertNotIn('backend_type',query)
            self.assertNotIn('state=',query)

    def test_manifest_and_archive_are_both_pinned_before_any_target_mutation(self):
        with tempfile.TemporaryDirectory() as root, patch.object(local_stack,'STATE',Path(root)),\
            patch.object(recovery,'schema_fingerprint',return_value=recovery.KNOWN_SCHEMA_SHA256):
            directory=Path(root)/'archive';directory.mkdir(mode=0o700)
            blob=b'authored-not-a-real-archive'
            manifest={'schema_version':1,'kind':'stopped-pg18.6-v1','archive_sha256':hashlib.sha256(blob).hexdigest(),
                      'archive_bytes':len(blob),'source_database':'source_ci','state':{}}
            recovery.private.write_json(directory/'manifest.json',manifest)
            (directory/'database.dump').write_bytes(blob);(directory/'database.dump').chmod(0o600)
            pin=recovery.private.sha(directory/'manifest.json')
            self.assertEqual(recovery.approved_archive(directory,pin)[1],blob)
            for bad in (None,'0'*64):
                with self.assertRaises(ValueError):recovery.approved_archive(directory,bad)
            with self.assertRaisesRegex(ValueError,'distinct_fresh_target'):
                recovery.restore(self.env(),directory,pin,Path(root)/'unused')
            self.assertFalse((Path(root)/'unused').exists())
            (directory/'database.dump').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'archive_changed'):
                recovery.approved_archive(directory,pin)

    def test_toc_rejects_executable_or_unknown_objects_even_for_a_pinned_archive(self):
        state={'indexes':[],'constraints':[]}
        for entry in ('FUNCTION pubburi unsafe()', 'TRIGGER pubburi t_user evil',
                      'TABLE pubburi unknown', 'EXTENSION - bad', 'RULE pubburi t_user evil'):
            listing='1; 1 1 '+entry+' owner\n'
            with self.subTest(entry=entry),patch.object(recovery,'client',return_value=listing.encode()), \
                    patch.object(recovery,'schema_fingerprint',return_value=recovery.KNOWN_SCHEMA_SHA256):
                with self.assertRaises(ValueError):recovery.archive_inventory(self.env(),b'archive',state)

    def test_toc_requires_each_exact_expected_object_once(self):
        shape={'indexes':[['t_user','t_user_pkey','index'],['t_user','authored_index','index']],
               'constraints':[['t_user','t_user_pkey','PRIMARY KEY (id)',True],
                              ['t_user','authored_check','CHECK (true)',True]]}
        for fingerprint, feedback, notebook, cart in ((recovery.KNOWN_SCHEMA_SHA256, False,False,False), (recovery.FEEDBACK_SCHEMA_SHA256, True,False,False),(recovery.NOTEBOOK_SCHEMA_SHA256,True,True,False),(recovery.CART_SCHEMA_SHA256,True,True,True)):
            with patch.object(recovery,'schema_fingerprint',return_value=fingerprint):
                expected=recovery.expected_toc(shape)
                self.assertEqual(feedback, 'TABLE DATA pubburi t_user_recommendation_feedback' in expected)
                self.assertEqual(notebook, 'TABLE DATA pubburi t_user_notebook' in expected)
                for table in ('t_user_cart','t_user_cart_item','t_user_cart_operation'):
                    self.assertEqual(cart, 'TABLE DATA pubburi '+table in expected)
                lines=[str(i)+'; 0 0 '+item+' owner' for i,item in enumerate(sorted(expected),1)]
                for listing,valid in ((lines,True),(lines[:-1],False),(lines+lines[:1],False),
                                      (lines[:-1]+lines[:1],False)):
                    with patch.object(recovery,'client',return_value=('\n'.join(listing)+'\n').encode()):
                        if valid:recovery.archive_inventory(self.env(),b'archive',shape)
                        else:
                            with self.assertRaises(ValueError):recovery.archive_inventory(self.env(),b'archive',shape)

    def test_only_exact_known_pg18_check_cast_roundtrip_is_equivalent(self):
        original=('t_product_feature_tag','ck_feature_tag_kind',"CHECK (kind::text = ANY (ARRAY['TASTE'::character varying, 'AROMA'::character varying]::text[]))",True)
        restored=(*original[:2],"CHECK (kind::text = ANY (ARRAY['TASTE'::character varying::text, 'AROMA'::character varying::text]))",True)
        self.assertEqual(recovery.known_check(restored),original)
        wrong=(*restored[:2],restored[2].replace('AROMA','OTHER'),True)
        self.assertEqual(recovery.known_check(wrong),wrong)
        renamed=('other_table',*restored[1:])
        self.assertEqual(recovery.known_check(renamed),renamed)

    def test_unknown_commit_or_post_commit_mismatch_never_emits_completion_or_cleans_target(self):
        manifest={'source_database':'original_ci','state':{}}
        conn=Mock();conn.__enter__=Mock(return_value=conn);conn.__exit__=Mock(return_value=False)
        conn.execute.return_value.fetchone.return_value=(False,)
        for failure in (TimeoutError('commit acknowledgement unknown'),None):
            with tempfile.TemporaryDirectory() as root,patch.object(local_stack,'STATE',Path(root)),\
                patch.object(recovery,'approved_archive',return_value=(manifest,b'archive')),\
                patch.object(recovery,'connect',return_value=conn),patch.object(recovery,'guard'),\
                patch.object(recovery,'reject_unversioned_objects'),\
                patch.object(recovery,'stopped_roles'),patch.object(recovery,'archive_inventory'),\
                patch.object(recovery,'state',return_value={'different':'committed'}),\
                patch.object(recovery,'client',side_effect=failure):
                target=Path(root)/'attempt'
                with self.assertRaises((TimeoutError,ValueError)):
                    recovery.restore(self.env(),'archive','a'*64,target)
                self.assertTrue((target/'attempt.json').exists())
                self.assertFalse((target/'completed.json').exists())
                self.assertFalse((target/'promotion.json').exists())

    def test_promotion_checks_prepared_work_and_rejects_source_connect_even_for_nologin_roles(self):
        connection = Mock()
        with patch.object(recovery, 'guard'), patch.object(recovery, 'stopped_roles') as stopped:
            connection.execute.return_value.fetchone.return_value = (True,)
            for source in (True, False):
                with self.assertRaisesRegex(ValueError, 'prepared_transactions_present'):
                    recovery.promotion_fence(connection, self.env(), source=source)
            stopped.assert_not_called()
            prepared, connect = Mock(), Mock()
            prepared.fetchone.return_value = (False,)
            connect.fetchone.return_value = (True,)
            connection.execute.side_effect = [prepared, connect]
            with self.assertRaisesRegex(ValueError, 'selected_source_connect_granted'):
                recovery.promotion_fence(connection, self.env(), source=True)

    def test_promotion_receipt_is_bound_to_the_physical_target_and_archive(self):
        original = self.env()
        receipt = recovery.promotion_receipt(original, 'a' * 64, 'source-uuid')
        for change in ({'PUBBURI_PG_DB_NAME': 'other_ci'}, {'PUBBURI_PG_CONTAINER_ID': 'b' * 64},
                       {'PUBBURI_PG_RUN_TOKEN': 'other-owned-marker'}, {'PUBBURI_PG_VALIDATION_LABEL': 'other-owned'}):
            self.assertNotEqual(receipt, recovery.promotion_receipt(dict(original, **change), 'a' * 64, 'source-uuid'))
        self.assertNotEqual(receipt, recovery.promotion_receipt(original, 'b' * 64, 'source-uuid'))
        self.assertNotIn(original['PUBBURI_PG_RUN_TOKEN'], json.dumps(receipt))
        self.assertNotIn(original['PUBBURI_PG_PASSWORD'], json.dumps(receipt))

    def test_known_table_and_sequence_set_include_all_raw_review_tags_and_control_receipts(self):
        self.assertEqual(len(recovery.PRIMARY),19)
        self.assertEqual(recovery.PRIMARY['t_comment_taste_tag'],'comment_id,tag')
        self.assertIn('migration_source_receipt',recovery.PRIMARY)
        self.assertIn('flyway_schema_history',recovery.PRIMARY)
        self.assertEqual(len(recovery.SEQUENCES),7)
