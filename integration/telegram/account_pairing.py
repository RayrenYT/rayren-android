"""Rayren device pairing core. No network listener or production DB mutation.

The HTTP adapter must rate-limit create/exchange, require HTTPS, disable body and
Authorization logging, and call approve only from the authenticated bot process.
Entitlement lookups execute on every feed request, never trust client Telegram IDs.
"""
import hashlib
import hmac
import re
import secrets
import sqlite3
import time
from contextlib import closing


def digest(value):
    return hashlib.sha256(value.encode("ascii")).hexdigest()


class PairingError(ValueError):
    pass


class Pairing:
    def __init__(self, path, clock=time.time):
        self.path, self.clock = str(path), clock
        with closing(self._db()) as db, db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS pairing (
                    code_hash TEXT PRIMARY KEY, challenge TEXT NOT NULL,
                    expires INTEGER NOT NULL, telegram_id INTEGER, used INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS devices (
                    token_hash TEXT PRIMARY KEY, telegram_id INTEGER NOT NULL,
                    expires INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
            """)

    def _db(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        return db

    def create(self, challenge):
        if not re.fullmatch(r"[0-9a-f]{64}", challenge):
            raise PairingError("invalid_challenge")
        code = secrets.token_urlsafe(24)  # Fits Telegram start's 64-character limit.
        expires = int(self.clock()) + 180
        with closing(self._db()) as db, db:
            db.execute("DELETE FROM pairing WHERE expires < ?", (int(self.clock()),))
            if db.execute("SELECT count(*) FROM pairing").fetchone()[0] >= 1000:
                raise PairingError("capacity")
            db.execute("INSERT INTO pairing(code_hash,challenge,expires) VALUES(?,?,?)",
                       (digest(code), challenge, expires))
        return {"code": code, "expires_at": expires}

    def approve(self, code, telegram_id):
        """Call only after a private-chat confirmation by update.effective_user.id."""
        if type(telegram_id) is not int or telegram_id <= 0:
            raise PairingError("invalid_identity")
        with closing(self._db()) as db, db:
            result = db.execute("""UPDATE pairing SET telegram_id=?
                WHERE code_hash=? AND expires>? AND used=0 AND telegram_id IS NULL""",
                (telegram_id, digest(code), int(self.clock())))
            if result.rowcount != 1:
                raise PairingError("invalid_or_already_approved")

    def exchange(self, code, verifier):
        if not re.fullmatch(r"[A-Za-z0-9_-]{43,128}", verifier):
            raise PairingError("invalid_verifier")
        with closing(self._db()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM pairing WHERE code_hash=?", (digest(code),)).fetchone()
            if not row or row["expires"] <= self.clock() or row["used"]:
                raise PairingError("invalid_or_expired")
            if not hmac.compare_digest(row["challenge"], digest(verifier)):
                raise PairingError("invalid_verifier")
            if row["telegram_id"] is None:
                return {"status": "pending"}
            token = secrets.token_urlsafe(32)
            db.execute("UPDATE pairing SET used=1 WHERE code_hash=?", (digest(code),))
            db.execute("INSERT INTO devices(token_hash,telegram_id,expires) VALUES(?,?,?)",
                       (digest(token), row["telegram_id"], int(self.clock()) + 30 * 86400))
            return {"status": "linked", "access_token": token, "expires_in": 30 * 86400}

    def owner(self, token):
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
            raise PairingError("unauthorized")
        with closing(self._db()) as db:
            row = db.execute("SELECT * FROM devices WHERE token_hash=?", (digest(token),)).fetchone()
        if not row or row["revoked"] or row["expires"] <= self.clock():
            raise PairingError("unauthorized")
        return row["telegram_id"]

    def revoke(self, token):
        with closing(self._db()) as db, db:
            db.execute("UPDATE devices SET revoked=1 WHERE token_hash=?", (digest(token),))

    def feed(self, token, lookup):
        """lookup(user_id) returns only that user's currently granted access.

        Empty access is a legitimate revocation; a lookup exception propagates
        instead of returning an empty feed or granting cached access.
        """
        user = self.owner(token)
        rights = lookup(user)
        return {"version": 1, "subscriptions": rights.get("subscriptions", []),
                "profiles": rights.get("profiles", [])}
