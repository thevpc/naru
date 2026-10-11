#!/bin/bash
EXPECTED="4
src/main.py
src/sub/worker.py
src/utils.py"

if [ ! -f "todo-report.txt" ]; then
    echo "FAIL: todo-report.txt does not exist"
    exit 1
fi

ACTUAL=$(cat todo-report.txt | sed -e 's/[[:space:]]*$//')
EXPECTED_TRIMMED=$(echo "$EXPECTED" | sed -e 's/[[:space:]]*$//')

if [ "$ACTUAL" = "$EXPECTED_TRIMMED" ]; then
    echo "PASS"
    exit 0
else
    echo "FAIL: Output mismatch"
    echo "Expected:"
    echo "$EXPECTED"
    echo "Actual:"
    echo "$ACTUAL"
    exit 1
fi
