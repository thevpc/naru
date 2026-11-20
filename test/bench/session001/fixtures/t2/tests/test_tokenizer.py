import unittest
from evaluator.tokenizer import tokenize

class TestTokenizer(unittest.TestCase):
    def test_basic(self):
        tokens = tokenize("1 + 2 * 3")
        self.assertEqual(len(tokens), 5)
        self.assertEqual(tokens[0].type, "NUMBER")
        self.assertEqual(tokens[1].type, "PLUS")

    def test_parentheses(self):
        tokens = tokenize("(10 - 2)")
        self.assertEqual(tokens[0].type, "LPAREN")
        self.assertEqual(tokens[-1].type, "RPAREN")

if __name__ == "__main__":
    unittest.main()
