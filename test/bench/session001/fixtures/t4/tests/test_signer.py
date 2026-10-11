import unittest
from crypto.signer import sign_payload

class TestSigner(unittest.TestCase):
    def test_sign(self):
        sig = sign_payload("msg", "key")
        self.assertEqual(len(sig), 64)

if __name__ == "__main__":
    unittest.main()
