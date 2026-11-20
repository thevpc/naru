#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "==> Resetting fixture /tmp/lab/proj2..."
python3 "$REPO/bench/parity-fixture.py" /tmp/lab/proj2

echo "==> Running ParityReplayTest via Maven..."
mvn test -Dtest=ParityReplayTest -pl test/naru-agent-test -f "$REPO/pom.xml"

echo "==> Parity probe replay complete. Results saved in $REPO/bench/parity-probes/results.json"
