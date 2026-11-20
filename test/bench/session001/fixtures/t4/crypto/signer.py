from .hash import calculate_hash

def sign_payload(payload: str, secret: str) -> str:
    return calculate_hash(payload + ":" + secret)
