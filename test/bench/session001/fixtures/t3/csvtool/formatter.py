def format_csv(rows, delimiter=","):
    return "\n".join(delimiter.join(str(f) for f in r) for r in rows)
