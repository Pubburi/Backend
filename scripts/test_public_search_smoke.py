import tempfile
import copy
import hashlib
import unittest
from pathlib import Path
from unittest.mock import patch
from public_search_smoke import verify,verify_fixture_login_transition


class PublicSearchSmokeSafetyTests(unittest.TestCase):
    def test_only_the_exact_authored_admin_legacy_hash_upgrade_is_allowed(self):
        password="authored-only";identifier="ci_jpa_authored"
        before={"t_user":[{"id":identifier,"password_hash":hashlib.sha256(password.encode()).hexdigest(),"auth_epoch":"unchanged-epoch","name":"unchanged","role":"ADMIN","stamps":0},{"id":"other","password_hash":"untouched","auth_epoch":"other-epoch"}],"t_product":[{"id":1,"price":1000}]}
        after=copy.deepcopy(before);after['t_user'][0]['password_hash']='$2a$authored-upgraded-only'
        self.assertTrue(verify_fixture_login_transition(before,after,identifier,password))
        self.assertEqual(before['t_user'][0]['password_hash'],hashlib.sha256(password.encode()).hexdigest())
        for field,value in [('auth_epoch','new-epoch'),('role','USER'),('name','changed'),('stamps',1)]:
            invalid=copy.deepcopy(after);invalid['t_user'][0][field]=value
            with self.assertRaises(AssertionError):verify_fixture_login_transition(before,invalid,identifier,password)
        invalid=copy.deepcopy(after);invalid['t_user'][1]['password_hash']='changed-other'
        with self.assertRaises(AssertionError):verify_fixture_login_transition(before,invalid,identifier,password)
        wrong_id_before=copy.deepcopy(before);wrong_id_after=copy.deepcopy(after)
        wrong_id_before['t_user'][0]['id']=wrong_id_after['t_user'][0]['id']='original-source-account'
        with self.assertRaises(AssertionError):verify_fixture_login_transition(wrong_id_before,wrong_id_after,'original-source-account',password)
        invalid=copy.deepcopy(after);invalid['t_product'][0]['price']=2000
        with self.assertRaises(AssertionError):verify_fixture_login_transition(before,invalid,identifier,password)
    def test_unsafe_database_index_or_public_reused_output_fails_before_resource_access(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);private=root/".pubburi";private.mkdir()
            with patch("postgresql_schema.ROOT",root),patch("postgresql_relay_smoke.fixture") as fixture:
                for index,dialect,output in (("pubburi-products-v1-test","unapproved",private),("pubburi-products-v1/other","postgresql",private),("pubburi-products-v1-test","postgresql",root)):
                    with self.assertRaises(ValueError):verify(None,"unused",output,{},index,dialect)
                (private/"public-search-postgresql-report.json").write_text("preserve previous evidence")
                with self.assertRaises(ValueError):verify(None,"unused",private,{},"pubburi-products-v1-test")
                fixture.assert_not_called()
                self.assertEqual((private/"public-search-postgresql-report.json").read_text(),"preserve previous evidence")

if __name__=="__main__":unittest.main()
