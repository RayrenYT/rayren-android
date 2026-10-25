import json
import tempfile
import unittest
from pathlib import Path
from flask import Flask, Response, request
from rayren_app_feed import FeedStore, register


class PersonalHttpTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.store=FeedStore(Path(self.tmp.name)/'feed.db')
        self.token=self.store.issue(123)
        self.app=Flask(__name__)
        self.access=True;self.fail=False;self.seen=[]
        def lookup(owner):
            self.seen.append(owner)
            return ('own-subscription',None) if self.access else (None,None)
        def main(token):
            self.assertEqual('own-subscription',token)
            self.assertIn('Rayren/',request.headers['User-Agent'])
            if self.fail:return Response('secret upstream error',status=500)
            return Response(json.dumps([{'outbounds':[]}]),content_type='application/json')
        register(self.app,main,self.store,lookup)
        self.client=self.app.test_client()

    def test_authenticated_feed_and_no_cache(self):
        r=self.client.get('/incy/rayren-app/'+self.token)
        self.assertEqual(200,r.status_code);self.assertEqual([123],self.seen)
        self.assertEqual('rayren-account-v1',r.json['format'])
        self.assertIn('no-store',r.headers['Cache-Control'])
        self.assertNotIn(self.token,r.get_data(as_text=True))

    def test_existing_response_transform_is_applied_before_wrapping(self):
        @self.app.after_request
        def current_routes(response):
            if request.path == '/incy/own-subscription':
                response.set_data(json.dumps([{'outbounds':[{'updated':'current route'}]}]))
            return response
        r=self.client.get('/incy/rayren-app/'+self.token)
        self.assertEqual(200,r.status_code)
        self.assertEqual([{'outbounds':[{'updated':'current route'}]}],r.json['documents'])

    def test_client_cannot_supply_another_identity(self):
        self.client.get('/incy/rayren-app/'+self.token+'?tg_id=999')
        self.assertEqual([123],self.seen)

    def test_wrong_token_never_calls_entitlement_lookup(self):
        r=self.client.get('/incy/rayren-app/'+'x'*43)
        self.assertEqual(403,r.status_code);self.assertEqual([],self.seen)

    def test_backend_failure_is_503_not_empty_feed_or_secret(self):
        self.fail=True
        r=self.client.get('/incy/rayren-app/'+self.token)
        self.assertEqual(503,r.status_code)
        self.assertNotIn('secret',r.get_data(as_text=True))

    def test_expired_entitlement_is_authoritative_empty(self):
        self.access=False
        r=self.client.get('/incy/rayren-app/'+self.token)
        self.assertEqual(200,r.status_code);self.assertEqual([],r.json['documents'])
        self.assertEqual([],r.json['reserve_profiles'])

if __name__=='__main__':unittest.main()
