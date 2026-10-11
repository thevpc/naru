def validate_row(row):
    if not row or not any(field.strip() for field in row):
        return False
    return True
