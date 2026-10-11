#!/bin/bash
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
