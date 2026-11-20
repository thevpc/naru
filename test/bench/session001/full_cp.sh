REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIRS=$(find "$REPO" -maxdepth 4 -name "classes" -type d | paste -sd ":" -)
DEP_CP=$(cat "$REPO/test/bench/classpath.txt")
echo "$TARGET_DIRS:$DEP_CP"
