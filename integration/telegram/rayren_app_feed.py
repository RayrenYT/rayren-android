"""Pilot personal subscription. Existing customer databases are opened read-only."""
import hashlib
import json
import logging
import re
import secrets
import sqlite3
import time
from contextlib import closing
from pathlib import Path

DB = '/opt/xui-balancer/rayren-app-access.db'
USERS = '/opt/xui-balancer/users.db'
BINDINGS = '/root/vless_config_aggregator/users.db'
TOKEN = re.compile(r'[A-Za-z0-9_-]{43}')


def readonly(path):
    db = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True, timeout=3)
    db.row_factory = sqlite3.Row
    return db


class AccessDenied(ValueError):
    pass


class FeedStore:
    def __init__(self, path=DB, clock=time.time):
        self.path, self.clock = str(path), clock
        with closing(sqlite3.connect(self.path, timeout=3)) as db, db:
            db.executescript('''CREATE TABLE IF NOT EXISTS accounts (
                tg_id INTEGER PRIMARY KEY, public_id TEXT NOT NULL UNIQUE);
                CREATE TABLE IF NOT EXISTS links (
                hash TEXT PRIMARY KEY, tg_id INTEGER NOT NULL, expires INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS links_owner ON links(tg_id);''')

    def issue(self, tg_id):
        if type(tg_id) is not int or tg_id <= 0:
            raise AccessDenied()
        token = secrets.token_urlsafe(32)
        with closing(sqlite3.connect(self.path, timeout=3)) as db, db:
            db.execute('DELETE FROM links WHERE expires<=?', (int(self.clock()),))
            db.execute('INSERT OR IGNORE INTO accounts VALUES(?,?)', (tg_id, secrets.token_hex(16)))
            # Cap links per account; opening the bot does not change customer access.
            count = db.execute('SELECT count(*) FROM links WHERE tg_id=?', (tg_id,)).fetchone()[0]
            if count >= 20:
                db.execute('DELETE FROM links WHERE hash IN (SELECT hash FROM links WHERE tg_id=? ORDER BY expires LIMIT ?)', (tg_id, count - 19))
            db.execute('INSERT INTO links VALUES(?,?,?)',
                       (hashlib.sha256(token.encode()).hexdigest(), tg_id, int(self.clock()) + 90 * 86400))
        return token

    def owner(self, token):
        if not TOKEN.fullmatch(token):
            raise AccessDenied()
        with closing(readonly(self.path)) as db:
            row = db.execute('''SELECT a.tg_id,a.public_id FROM links l
                JOIN accounts a ON a.tg_id=l.tg_id WHERE l.hash=? AND l.expires>?''',
                (hashlib.sha256(token.encode()).hexdigest(), int(self.clock()))).fetchone()
        if not row:
            raise AccessDenied()
        return dict(row)

    def revoke(self, tg_id):
        with closing(sqlite3.connect(self.path, timeout=3)) as db, db:
            db.execute('DELETE FROM links WHERE tg_id=?', (tg_id,))


def rights(tg_id, users=USERS, bindings=BINDINGS, clock=time.time, reserve_profile=None):
    with closing(readonly(bindings)) as db:
        binding = db.execute('SELECT sub_id FROM tg_users WHERE tg_id=?', (tg_id,)).fetchone()
    if not binding:
        return None, None
    token = binding['sub_id']
    with closing(readonly(users)) as db:
        user = db.execute('SELECT username,enabled,telegram_id,sub_token FROM users WHERE sub_token=?', (token,)).fetchone()
        if user and (not user['enabled'] or user['telegram_id'] not in (None, tg_id)):
            return None, None
        entitlement = db.execute('SELECT subid,plan,paid_until FROM billing_entitlements WHERE tg_id=?', (tg_id,)).fetchone()
        if entitlement and (entitlement['paid_until'] <= clock() or entitlement['subid'] != token):
            return None, None
        member = db.execute('''SELECT m.username,m.subid FROM panel_room_members m
            JOIN telemost_rooms r ON r.slot=m.slot AND r.enabled=1 WHERE m.telegram_id=?''', (tg_id,)).fetchone()
        uri = None
        if member and member['subid'] == token and (not user or member['username'] == user['username']) and (not entitlement or entitlement['plan'] == 'advanced'):
            if reserve_profile is None:
                import openflux_routes
                reserve_profile = lambda connection, username: openflux_routes.profile(connection, username) if openflux_routes.active(connection) else None
            uri = reserve_profile(db, member['username'])
            if uri is not None and (not isinstance(uri, str) or not uri.startswith(('openflux://', 'olcrtc://')) or len(uri) > 65536):
                raise ValueError('invalid reserve profile')
    return token, uri


def bundle(store, token, lookup, fetch_main):
    owner = store.owner(token)
    main, reserve = lookup(owner['tg_id'])
    documents = fetch_main(main) if main else []
    if not isinstance(documents, list) or (main and not documents):
        raise ValueError('invalid main subscription')
    return {'format': 'rayren-account-v1', 'version': 1, 'account_id': owner['public_id'],
            'documents': documents, 'reserve_profiles': [reserve] if main and reserve else []}


def register(app, main_handler, store=None, lookup=None):
    from flask import Response, request
    store = store or FeedStore()
    lookup = lookup or rights
    class PrivateAccessLog(logging.Filter):
        def filter(self, record):
            return '/incy/rayren-app/' not in record.getMessage()
    for name in ('werkzeug', 'gunicorn.access'):
        logging.getLogger(name).addFilter(PrivateAccessLog())

    def fetch_main(token):
        # Use the existing authenticated subscription generator, without another HTTP hop.
        with app.test_request_context('/incy/' + token, headers={'User-Agent': 'Rayren/0.1.7 Android (Happ/JSON-compatible)', 'Accept': 'application/json'}):
            # Apply the existing response pipeline too (e.g. current REALITY SNI/shortId).
            response = app.process_response(app.make_response(main_handler(token)))
            if response.status_code != 200 or len(response.data) > 2 * 1024 * 1024:
                raise ValueError('main unavailable')
            return json.loads(response.get_data(as_text=True))

    @app.get('/incy/rayren-app/<token>')
    def rayren_personal_subscription(token):
        headers = {'Cache-Control': 'no-store, private', 'Referrer-Policy': 'no-referrer',
                   'X-Content-Type-Options': 'nosniff', 'profile-title': 'Rayren account',
                   'subscription-userinfo': ''}
        try:
            result = bundle(store, token, lookup, fetch_main)
            data = json.dumps(result, ensure_ascii=False).encode('utf-8')
            if len(data) > 2 * 1024 * 1024:
                raise ValueError('payload too large')
            return Response(data, content_type='application/json; charset=utf-8', headers=headers)
        except AccessDenied:
            return Response('Access denied', status=403, headers=headers)
        except Exception:
            # No URI, token, user identity, provider secret or traceback in access errors.
            return Response('Subscription temporarily unavailable', status=503, headers=headers)
