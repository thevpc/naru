# Decoy comment:
# TODO: consider formatting results into scientific notation or hex

def format_result(val, precision=2):
    if isinstance(val, float) and val.is_integer():
        return str(int(val))
    return f"{val:.{precision}f}"
