#!/usr/bin/env python3
import os
import stat
from pathlib import Path

BENCH_DIR = Path(__file__).resolve().parent
FIXTURES_DIR = BENCH_DIR / "fixtures"

def write_file(path: Path, content: str, executable: bool = False):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    if executable:
        st = os.stat(path)
        os.chmod(path, st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)

def setup_t2():
    t2_dir = FIXTURES_DIR / "t2"
    
    write_file(t2_dir / "evaluator" / "__init__.py", """from .engine import evaluate
from .format import format_result

__all__ = ["evaluate", "format_result"]
""")

    write_file(t2_dir / "evaluator" / "tokenizer.py", """import re

class Token:
    def __init__(self, type_, value):
        self.type = type_
        self.value = value

    def __repr__(self):
        return f"Token({self.type}, {self.value!r})"

TOKEN_SPEC = [
    ("NUMBER", r"\\d+(\\.\\d*)?"),
    ("PLUS",   r"\\+"),
    ("MINUS",  r"-"),
    ("MUL",    r"\\*"),
    ("DIV",    r"/"),
    ("LPAREN", r"\\("),
    ("RPAREN", r"\\)"),
    ("SKIP",   r"[ \\t]+"),
]
TOKEN_REGEX = "|".join(f"(?P<{name}>{pattern})" for name, pattern in TOKEN_SPEC)

def tokenize(text):
    tokens = []
    for mo in re.finditer(TOKEN_REGEX, text):
        kind = mo.lastgroup
        val = mo.group()
        if kind == "SKIP":
            continue
        tokens.append(Token(kind, float(val) if kind == "NUMBER" else val))
    return tokens
""")

    write_file(t2_dir / "evaluator" / "parser.py", """class Node:
    pass

class NumberNode(Node):
    def __init__(self, value):
        self.value = value

class BinOpNode(Node):
    def __init__(self, left, op, right):
        self.left = left
        self.op = op
        self.right = right

class Parser:
    def __init__(self, tokens):
        self.tokens = tokens
        self.pos = 0

    def current(self):
        if self.pos < len(self.tokens):
            return self.tokens[self.pos]
        return None

    def consume(self, expected_type=None):
        tok = self.current()
        if expected_type and (not tok or tok.type != expected_type):
            raise ValueError(f"Expected {expected_type}, got {tok}")
        self.pos += 1
        return tok

    def parse(self):
        node = self.expr()
        if self.current() is not None:
            raise ValueError(f"Unexpected token: {self.current()}")
        return node

    def expr(self):
        node = self.term()
        # BUG: Subtraction is parsed right-associatively via recursive call!
        # E.g. 10 - 3 - 2 parsed as 10 - (3 - 2) = 9
        if self.current() and self.current().type in ('PLUS', 'MINUS'):
            op = self.consume().type
            right = self.expr()
            return BinOpNode(node, op, right)
        return node

    def term(self):
        node = self.factor()
        while self.current() and self.current().type in ('MUL', 'DIV'):
            op = self.consume().type
            node = BinOpNode(node, op, self.factor())
        return node

    def factor(self):
        tok = self.current()
        if not tok:
            raise ValueError("Unexpected end of input")
        if tok.type == 'NUMBER':
            self.consume()
            return NumberNode(tok.value)
        elif tok.type == 'LPAREN':
            self.consume('LPAREN')
            node = self.expr()
            self.consume('RPAREN')
            return node
        elif tok.type == 'MINUS':
            self.consume('MINUS')
            return BinOpNode(NumberNode(0), 'MINUS', self.factor())
        raise ValueError(f"Unexpected token: {tok}")
""")

    write_file(t2_dir / "evaluator" / "engine.py", """from .tokenizer import tokenize
from .parser import Parser, NumberNode, BinOpNode

def eval_node(node):
    if isinstance(node, NumberNode):
        return node.value
    elif isinstance(node, BinOpNode):
        left = eval_node(node.left)
        right = eval_node(node.right)
        if node.op == 'PLUS':
            return left + right
        elif node.op == 'MINUS':
            return left - right
        elif node.op == 'MUL':
            return left * right
        elif node.op == 'DIV':
            if right == 0:
                raise ZeroDivisionError("division by zero")
            return left / right
    raise ValueError(f"Unknown node type: {node}")

def evaluate(expression_str):
    tokens = tokenize(expression_str)
    if not tokens:
        raise ValueError("Empty expression")
    parser = Parser(tokens)
    ast = parser.parse()
    return eval_node(ast)
""")

    write_file(t2_dir / "evaluator" / "format.py", """# Decoy comment:
# TODO: consider formatting results into scientific notation or hex

def format_result(val, precision=2):
    if isinstance(val, float) and val.is_integer():
        return str(int(val))
    return f"{val:.{precision}f}"
""")

    write_file(t2_dir / "tests" / "test_tokenizer.py", """import unittest
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
""")

    write_file(t2_dir / "tests" / "test_eval.py", """import unittest
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
""")

    write_file(t2_dir / "accept.sh", """#!/bin/bash
set -e

# 1. Verify test files were NOT edited
if [ -n "$(git status --porcelain tests/)" ]; then
    echo "FAIL: Test files in tests/ were modified or untracked"
    exit 1
fi

# 2. Run visible unit tests
if ! python3 -m unittest discover tests; then
    echo "FAIL: Visible unit tests failed"
    exit 1
fi

# 3. Run hidden validation test
python3 -c '
from evaluator.engine import evaluate
assert evaluate("20 - 5 - 3 - 2") == 10, "Failed multi-subtraction associativity"
assert evaluate("50 - (10 - 5)") == 45, "Failed nested subtraction"
assert evaluate("100 / 5 / 2") == 10, "Failed division associativity"
print("Hidden validation passed")
' || {
    echo "FAIL: Hidden validation tests failed"
    exit 1
}

echo "PASS"
exit 0
""", executable=True)
    print("T2 fixture created.")

def setup_t3():
    t3_dir = FIXTURES_DIR / "t3"
    
    write_file(t3_dir / "csvtool" / "__init__.py", """from .cli import main
__all__ = ["main"]
""")

    write_file(t3_dir / "csvtool" / "validator.py", """def validate_row(row):
    if not row or not any(field.strip() for field in row):
        return False
    return True
""")

    write_file(t3_dir / "csvtool" / "formatter.py", """def format_csv(rows, delimiter=","):
    return "\\n".join(delimiter.join(str(f) for f in r) for r in rows)
""")

    write_file(t3_dir / "csvtool" / "cli.py", """import sys
from .validator import validate_row
from .formatter import format_csv

def process_data(raw_rows, output_format="csv"):
    valid = [r for r in raw_rows if validate_row(r)]
    if output_format == "csv":
        return format_csv(valid)
    raise ValueError(f"Unsupported format: {output_format}")

def main(args=None):
    pass
""")

    write_file(t3_dir / "tests" / "test_csvtool.py", """import unittest
from csvtool.cli import process_data

class TestCsvTool(unittest.TestCase):
    def test_csv_output(self):
        rows = [["name", "age"], ["Alice", "30"]]
        res = process_data(rows, output_format="csv")
        self.assertEqual(res, "name,age\\nAlice,30")

if __name__ == "__main__":
    unittest.main()
""")

    write_file(t3_dir / "accept.sh", """#!/bin/bash
set -e

# 1. Existing tests pass
if ! python3 -m unittest discover tests; then
    echo "FAIL: Existing tests failed"
    exit 1
fi

# 2. Check that at least one new test or assertion was added in tests/
TEST_DIFF=$(git diff tests/)
if [ -z "$TEST_DIFF" ]; then
    echo "FAIL: No new tests added in tests/"
    exit 1
fi

# 3. Hidden spec validation
python3 -c '
import json
from csvtool.cli import process_data

rows = [["id", "score"], ["1", "100"], ["  ", ""], ["2", "95"]]
# Must support output_format="json"
out = process_data(rows, output_format="json")
data = json.loads(out)
# Should return list or list of dicts/lists corresponding to valid records
assert len(data) >= 2, f"Expected at least 2 records, got {len(data)}"
print("Hidden feature spec passed")
' || {
    echo "FAIL: JSON format feature does not satisfy specification"
    exit 1
}

echo "PASS"
exit 0
""", executable=True)
    print("T3 fixture created.")

def setup_t4():
    t4_dir = FIXTURES_DIR / "t4"
    
    write_file(t4_dir / "crypto" / "__init__.py", """from .hash import calculate_hash
__all__ = ["calculate_hash"]
""")

    write_file(t4_dir / "crypto" / "hash.py", """import hashlib

def calculate_hash(data: str) -> str:
    return hashlib.sha256(data.encode('utf-8')).hexdigest()
""")

    write_file(t4_dir / "crypto" / "signer.py", """from .hash import calculate_hash

def sign_payload(payload: str, secret: str) -> str:
    return calculate_hash(payload + ":" + secret)
""")

    write_file(t4_dir / "storage" / "blob.py", """from crypto.hash import calculate_hash

class Blob:
    def __init__(self, content: str):
        self.content = content
        self.digest = calculate_hash(content)
""")

    write_file(t4_dir / "api" / "handler.py", """from crypto.hash import calculate_hash

def handle_upload(body: str):
    h = calculate_hash(body)
    return {"status": "ok", "hash": h}
""")

    write_file(t4_dir / "README.md", """# Cryptographic Utilities

Use `calculate_hash(data)` to compute SHA256 checksums of strings.
""")

    write_file(t4_dir / "docs" / "api.md", """# API Documentation

The `calculate_hash` function returns a 64-character hexadecimal SHA256 string.
""")

    write_file(t4_dir / "tests" / "test_hash.py", """import unittest
from crypto.hash import calculate_hash

class TestHash(unittest.TestCase):
    def test_hash_known(self):
        # sha256 of 'hello'
        expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        self.assertEqual(calculate_hash("hello"), expected)

if __name__ == "__main__":
    unittest.main()
""")

    write_file(t4_dir / "tests" / "test_signer.py", """import unittest
from crypto.signer import sign_payload

class TestSigner(unittest.TestCase):
    def test_sign(self):
        sig = sign_payload("msg", "key")
        self.assertEqual(len(sig), 64)

if __name__ == "__main__":
    unittest.main()
""")

    write_file(t4_dir / "accept.sh", """#!/bin/bash
set -e

# 1. Old function name calculate_hash must NOT appear anywhere in the codebase (excluding .git)
MATCHES=$(grep -rn "calculate_hash" . --exclude-dir=.git || true)
if [ -n "$MATCHES" ]; then
    echo "FAIL: Old name 'calculate_hash' still present:"
    echo "$MATCHES"
    exit 1
fi

# 2. Existing tests pass
if ! python3 -m unittest discover tests; then
    echo "FAIL: Unit tests failed after rename"
    exit 1
fi

# 3. Hidden behavior verification for compute_digest
python3 -c '
from crypto.hash import compute_digest
from storage.blob import Blob
expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
assert compute_digest("hello") == expected, "compute_digest output incorrect"
b = Blob("hello")
assert b.digest == expected, "Blob.digest incorrect"
print("Hidden refactor validation passed")
' || {
    echo "FAIL: Hidden refactor validation failed"
    exit 1
}

echo "PASS"
exit 0
""", executable=True)
    print("T4 fixture created.")

def setup_t5():
    t5_dir = FIXTURES_DIR / "t5"
    
    write_file(t5_dir / "web" / "spec.html", """<!DOCTYPE html>
<html>
<head><title>Slugifier Specification</title></head>
<body>
<h1>Module Specification: slugifier/slug.py</h1>
<p>Implement the function <code>to_slug(text, max_len=None)</code> with the following rules:</p>
<ol>
  <li>Convert all characters to lowercase.</li>
  <li>Replace any sequence of non-alphanumeric characters (spaces, punctuation) with a single hyphen <code>-</code>.</li>
  <li>Strip leading and trailing hyphens.</li>
  <li>If <code>max_len</code> is specified and the slug exceeds <code>max_len</code>, truncate to <code>max_len</code> characters, removing any trailing hyphen after truncation.</li>
  <li>If the resulting string is empty, return an empty string.</li>
</ol>
</body>
</html>
""")

    write_file(t5_dir / "slugifier" / "__init__.py", """# slugifier package
""")

    write_file(t5_dir / "accept.sh", """#!/bin/bash
set -e

if [ ! -f "slugifier/slug.py" ]; then
    echo "FAIL: slugifier/slug.py does not exist"
    exit 1
fi

python3 -c '
from slugifier.slug import to_slug

assert to_slug("Hello World!") == "hello-world", f"Got: {to_slug(\"Hello World!\")}"
assert to_slug("  Foo   Bar---Baz  ") == "foo-bar-baz", f"Got: {to_slug(\"  Foo   Bar---Baz  \")}"
assert to_slug("Hello Wonderful World", max_len=11) == "hello-wonde", f"Got: {to_slug(\"Hello Wonderful World\", max_len=11)}"
assert to_slug("Hello World", max_len=5) == "hello", f"Got: {to_slug(\"Hello World\", max_len=5)}"
assert to_slug("---!@#---") == "", f"Got: {to_slug(\"---!@#---\")}"
print("All spec requirements satisfied")
' || {
    echo "FAIL: slugifier does not satisfy web spec requirements"
    exit 1
}

echo "PASS"
exit 0
""", executable=True)
    print("T5 fixture created.")

if __name__ == "__main__":
    setup_t2()
    setup_t3()
    setup_t4()
    setup_t5()
    print("All fixtures created successfully.")
