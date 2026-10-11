from .tokenizer import tokenize
from .parser import Parser, NumberNode, BinOpNode

def eval_node(node):
    if isinstance(node, NumberNode):
        return node.value
    elif isinstance(node, BinOpNode):
        left = eval_node(node.left)
        right = eval_node(node.right)
        if node.op == 'PLUS':
            return left + right
        elif node.op == 'MINUS':
            return left - right
        elif node.op == 'MUL':
            return left * right
        elif node.op == 'DIV':
            if right == 0:
                raise ZeroDivisionError("division by zero")
            return left / right
    raise ValueError(f"Unknown node type: {node}")

def evaluate(expression_str):
    tokens = tokenize(expression_str)
    if not tokens:
        raise ValueError("Empty expression")
    parser = Parser(tokens)
    ast = parser.parse()
    return eval_node(ast)
