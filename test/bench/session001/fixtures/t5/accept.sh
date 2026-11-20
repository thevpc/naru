#!/bin/bash
set -e

if [ ! -f "slugifier/slug.py" ]; then
    echo "FAIL: slugifier/slug.py does not exist"
    exit 1
fi

python3 -c '
from slugifier.slug import to_slug

assert to_slug("Hello World!") == "hello-world", f"Got: {to_slug("Hello World!")}"
assert to_slug("  Foo   Bar---Baz  ") == "foo-bar-baz", f"Got: {to_slug("  Foo   Bar---Baz  ")}"
assert to_slug("Hello Wonderful World", max_len=11) == "hello-wonde", f"Got: {to_slug("Hello Wonderful World", max_len=11)}"
assert to_slug("Hello World", max_len=5) == "hello", f"Got: {to_slug("Hello World", max_len=5)}"
assert to_slug("---!@#---") == "", f"Got: {to_slug("---!@#---")}"
print("All spec requirements satisfied")
' || {
    echo "FAIL: slugifier does not satisfy web spec requirements"
    exit 1
}

echo "PASS"
exit 0
