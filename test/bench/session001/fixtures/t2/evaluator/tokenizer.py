import re

class Token:
    def __init__(self, type_, value):
        self.type = type_
        self.value = value

    def __repr__(self):
        return f"Token({self.type}, {self.value!r})"

TOKEN_SPEC = [
    ("NUMBER", r"\d+(\.\d*)?"),
    ("PLUS",   r"\+"),
    ("MINUS",  r"-"),
    ("MUL",    r"\*"),
    ("DIV",    r"/"),
    ("LPAREN", r"\("),
    ("RPAREN", r"\)"),
    ("SKIP",   r"[ \t]+"),
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
