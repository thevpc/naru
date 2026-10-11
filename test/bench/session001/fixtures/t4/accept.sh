#!/bin/bash
set -e

# 1. Old function name calculate_hash must NOT appear anywhere in the codebase (excluding .git)
MATCHES=$(grep -rn "calculate_hash" . --exclude-dir=.git --exclude="accept.sh" || true)
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
