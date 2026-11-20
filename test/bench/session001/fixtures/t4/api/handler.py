from crypto.hash import calculate_hash

def handle_upload(body: str):
    h = calculate_hash(body)
    return {"status": "ok", "hash": h}
