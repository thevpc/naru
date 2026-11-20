#!/usr/bin/env python3
"""Build the fixture repo used by opencode-golden-probes.json.

Usage: python3 parity-fixture.py <base-dir>

The golden file's arguments use the absolute base /tmp/lab/proj2. To replay the
probes against another tool, create the fixture under your own base dir and
replace the string /tmp/lab/proj2 with that dir in every probe argument.

Web probes (P37-P40) expect a static server:
    python3 -m http.server 8099 --directory <base-dir>/web
"""
import os, shutil, subprocess, sys

B = os.path.abspath(sys.argv[1])
shutil.rmtree(B, ignore_errors=True)
os.makedirs(B + '/sub/deep')
os.makedirs(B + '/web')


def w(path, text):
    with open(os.path.join(B, path), 'w') as f:
        f.write(text)


w('a.txt', ''.join(f'line{i}\n' for i in range(1, 6)))
w('dup.txt', 'foo\nbar\nfoo\n')
w('exist.txt', 'original\n')
w('big.txt', ''.join(f'L{i}\n' for i in range(1, 3001)))
w('long.txt', 'x' * 5000 + '\n')
with open(B + '/bin.dat', 'wb') as f:
    f.write(bytes(range(256)) * 4)
w('sub/x.java', '// TODO fix\nclass X { }\n')
w('sub/deep/y.java', 'class Y { /* TODO later */ }\n')
w('z.md', '# doc\nTODO in md\n')
for i, name in enumerate(['e1.txt', 'e2.txt', 'e3.txt']):
    w(name, 'foo\n')
    os.utime(os.path.join(B, name), (1700000000 + i * 1000,) * 2)
w('web/spec.html',
  '<html><head><title>Spec</title><script>var x=1;</script></head><body>'
  '<h1>Spec Title</h1><p>Hello <a href="/other">link</a></p>'
  '<ul><li>one</li><li>two</li></ul><pre>code block</pre></body></html>')

subprocess.run(['git', 'init', '-q', B], check=True)
subprocess.run(['git', '-C', B, 'add', '-A'], check=True)
subprocess.run(['git', '-C', B, '-c', 'user.email=a@b', '-c', 'user.name=n',
                'commit', '-qm', 'init'], check=True)
print('fixture ready at', B)
