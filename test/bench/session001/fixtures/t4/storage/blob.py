from crypto.hash import calculate_hash

class Blob:
    def __init__(self, content: str):
        self.content = content
        self.digest = calculate_hash(content)
