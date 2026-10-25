import json
from contextlib import closing
import sqlite3
import tempfile
import unittest
from pathlib import Path
from rayren_app_feed import FeedStore, AccessDenied, bundle, rights, readonly


class PersonalFeedTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.now = 1000
        self.store = FeedStore(self.root/'links.db', lambda:self.now)
        self.users, self.bindings = self.root/'users.db', self.root/'bot.db'
        with closing(sqlite3.connect(self.bindings)) as db, db:
            db.execute('CREATE TABLE tg_users(tg_id INTEGER PRIMARY KEY,sub_id TEXT)')
            db.executemany('INSERT INTO tg_users VALUES(?,?)', [(101,'main-a'),(202,'main-b'),(303,'main-c')])
        with closing(sqlite3.connect(self.users)) as db, db:
            db.executescript('''CREATE TABLE users(username TEXT,enabled INTEGER,telegram_id INTEGER,sub_token TEXT);
              CREATE TABLE billing_entitlements(tg_id INTEGER,subid TEXT,plan TEXT,paid_until INTEGER);
              CREATE TABLE panel_room_members(telegram_id INTEGER,username TEXT,subid TEXT,slot TEXT);
              CREATE TABLE telemost_rooms(slot TEXT,enabled INTEGER);''')
            db.executemany('INSERT INTO users VALUES(?,1,?,?)', [('a',101,'main-a'),('b',202,'main-b'),('c',303,'main-c')])
            db.executemany('INSERT INTO billing_entitlements VALUES(?,?,?,9999)', [(101,'main-a','basic'),(202,'main-b','advanced')])
            db.execute("INSERT INTO telemost_rooms VALUES('room',1)")
            db.executemany('INSERT INTO panel_room_members VALUES(?,?,?,?)', [(202,'b','main-b','room'),(303,'c','main-c','room')])

    def lookup(self, user):
        return rights(user,self.users,self.bindings,lambda:self.now,lambda db,name:'openflux://fixture-'+name)

    def feed(self, token):
        return bundle(self.store,token,self.lookup,lambda main:[{'owner':main}])

    def change(self, sql):
        with closing(sqlite3.connect(self.users)) as db, db:db.execute(sql)

    def test_two_accounts_cannot_receive_each_others_subscription_or_cups(self):
        a,b=self.store.issue(101),self.store.issue(202)
        self.assertEqual([{'owner':'main-a'}],self.feed(a)['documents'])
        self.assertEqual([],self.feed(a)['reserve_profiles'])
        self.assertEqual([{'owner':'main-b'}],self.feed(b)['documents'])
        self.assertEqual(['openflux://fixture-b'],self.feed(b)['reserve_profiles'])
        self.assertNotEqual(self.feed(a)['account_id'],self.feed(b)['account_id'])

    def test_reimport_has_stable_account_id_but_distinct_secret_tokens(self):
        a,b=self.store.issue(101),self.store.issue(101)
        self.assertNotEqual(a,b)
        self.assertEqual(self.feed(a)['account_id'],self.feed(b)['account_id'])
        with closing(readonly(self.store.path)) as db:
            values=str([tuple(r) for r in db.execute('SELECT * FROM links')])
        self.assertNotIn(a,values)
        self.assertNotIn(b,values)

    def test_revoked_expired_and_forged_tokens_are_denied(self):
        token=self.store.issue(101)
        self.store.revoke(101)
        with self.assertRaises(AccessDenied):self.feed(token)
        token=self.store.issue(101);self.now+=90*86400
        with self.assertRaises(AccessDenied):self.feed(token)
        with self.assertRaises(AccessDenied):self.feed('x'*43)

    def test_expired_access_is_authoritative_empty_and_never_fetches_main(self):
        token=self.store.issue(202)
        self.change('UPDATE billing_entitlements SET paid_until=0 WHERE tg_id=202')
        feed=bundle(self.store,token,self.lookup,lambda _:self.fail('Expired main fetched'))
        self.assertEqual([],feed['documents']);self.assertEqual([],feed['reserve_profiles'])

    def test_disabled_and_removed_binding_clear_account(self):
        token=self.store.issue(101)
        self.change('UPDATE users SET enabled=0 WHERE telegram_id=101')
        self.assertEqual([],self.feed(token)['documents'])
        with closing(sqlite3.connect(self.bindings)) as db, db:db.execute('DELETE FROM tg_users WHERE tg_id=101')
        self.assertEqual([],self.feed(token)['documents'])

    def test_cups_revocation_plan_change_and_room_disable_are_rechecked(self):
        token=self.store.issue(202)
        self.change("UPDATE billing_entitlements SET plan='basic' WHERE tg_id=202")
        self.assertEqual([],self.feed(token)['reserve_profiles'])
        self.change("UPDATE billing_entitlements SET plan='advanced' WHERE tg_id=202")
        self.change('UPDATE telemost_rooms SET enabled=0')
        self.assertEqual([],self.feed(token)['reserve_profiles'])
        self.change('UPDATE telemost_rooms SET enabled=1')
        self.change('DELETE FROM panel_room_members WHERE telegram_id=202')
        self.assertEqual([],self.feed(token)['reserve_profiles'])

    def test_old_customer_without_billing_record_keeps_existing_access(self):
        self.assertEqual(['openflux://fixture-c'],self.feed(self.store.issue(303))['reserve_profiles'])

    def test_mismatched_owner_and_membership_never_grant_other_account(self):
        token=self.store.issue(202)
        self.change("UPDATE panel_room_members SET subid='main-a' WHERE telegram_id=202")
        self.assertEqual([],self.feed(token)['reserve_profiles'])
        self.change('UPDATE users SET telegram_id=101 WHERE username=\'b\'')
        self.assertEqual([],self.feed(token)['documents'])

    def test_backend_error_does_not_turn_into_empty_revocation(self):
        with self.assertRaises(RuntimeError):
            bundle(self.store,self.store.issue(101),self.lookup,lambda _:(_ for _ in ()).throw(RuntimeError('backend')))
        with self.assertRaises(ValueError):bundle(self.store,self.store.issue(101),self.lookup,lambda _:[])

    def test_customer_databases_are_not_modified(self):
        before=[p.read_bytes() for p in (self.users,self.bindings)]
        self.feed(self.store.issue(202))
        self.assertEqual(before,[p.read_bytes() for p in (self.users,self.bindings)])

if __name__=='__main__':unittest.main()
