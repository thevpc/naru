class Node:
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
