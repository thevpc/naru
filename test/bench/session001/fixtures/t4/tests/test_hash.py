import unittest
from crypto.hash import calculate_hash

class TestHash(unittest.TestCase):
    def test_hash_known(self):
        # sha256 of 'hello'
        expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        self.assertEqual(calculate_hash("hello"), expected)

if __name__ == "__main__":
    unittest.main()
