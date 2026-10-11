#!/bin/bash
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
