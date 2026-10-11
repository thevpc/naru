import unittest
from evaluator.engine import evaluate

class TestEval(unittest.TestCase):
    def test_simple_addition(self):
        self.assertEqual(evaluate("1 + 2"), 3)

    def test_precedence(self):
        self.assertEqual(evaluate("2 + 3 * 4"), 14)

    def test_parentheses(self):
        self.assertEqual(evaluate("(2 + 3) * 4"), 20)

    def test_subtraction_associativity(self):
        # 10 - 3 - 2 must be (10 - 3) - 2 = 5, NOT 10 - (3 - 2) = 9
        self.assertEqual(evaluate("10 - 3 - 2"), 5)

if __name__ == "__main__":
    unittest.main()
