import secrets
import tempfile
import unittest
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from account_pairing import Pairing, PairingError, digest


class PairingTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.now = 1000
        self.path = Path(self.folder.name) / "pair.db"
        self.pair = Pairing(self.path, lambda: self.now)
        self.verifier = secrets.token_urlsafe(32)
        self.code = self.pair.create(digest(self.verifier))["code"]

    def linked(self, user=42):
        self.pair.approve(self.code, user)
        return self.pair.exchange(self.code, self.verifier)["access_token"]

    def test_no_access_before_bot_approval(self):
        self.assertEqual({"status": "pending"}, self.pair.exchange(self.code, self.verifier))

    def test_code_alone_does_not_grant_access(self):
        self.pair.approve(self.code, 42)
        with self.assertRaises(PairingError):
            self.pair.exchange(self.code, secrets.token_urlsafe(32))
        self.assertEqual("linked", self.pair.exchange(self.code, self.verifier)["status"])

    def test_one_use_and_no_rebinding(self):
        self.linked()
        with self.assertRaises(PairingError):
            self.pair.exchange(self.code, self.verifier)
        with self.assertRaises(PairingError):
            self.pair.approve(self.code, 99)

    def test_expiry(self):
        self.now += 180
        with self.assertRaises(PairingError):
            self.pair.approve(self.code, 42)

    def test_feed_uses_verified_user_and_current_cups_entitlement(self):
        token = self.linked()
        users = []
        rights = {"subscriptions": [{"id": "main", "url": "https://example.test/main"}],
                  "profiles": ["openflux://test-only"]}
        def lookup(user):
            users.append(user)
            return rights
        self.assertEqual(1, len(self.pair.feed(token, lookup)["profiles"]))
        rights["profiles"] = []
        self.assertEqual([], self.pair.feed(token, lookup)["profiles"])
        self.assertEqual([42, 42], users)

    def test_failure_does_not_return_stale_entitlements(self):
        token = self.linked()
        def unavailable(_):
            raise OSError("db unavailable")
        with self.assertRaises(OSError):
            self.pair.feed(token, unavailable)

    def test_tokens_not_stored_in_plaintext_and_revocation_works(self):
        token = self.linked()
        self.assertNotIn(token.encode(), self.path.read_bytes())
        self.assertNotIn(self.code.encode(), self.path.read_bytes())
        self.pair.revoke(token)
        with self.assertRaises(PairingError):
            self.pair.owner(token)

    def test_racing_exchanges_only_issue_one_token(self):
        self.pair.approve(self.code, 42)
        def exchange(_):
            try:
                return self.pair.exchange(self.code, self.verifier)["status"]
            except PairingError:
                return "denied"
        with ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual(["denied", "linked"], sorted(pool.map(exchange, [0, 1])))

    def test_expired_device_token_denied(self):
        token = self.linked()
        self.now += 30 * 86400
        with self.assertRaises(PairingError):
            self.pair.owner(token)


if __name__ == "__main__":
    unittest.main()
