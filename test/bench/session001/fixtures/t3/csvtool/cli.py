import sys
from .validator import validate_row
from .formatter import format_csv

def process_data(raw_rows, output_format="csv"):
    valid = [r for r in raw_rows if validate_row(r)]
    if output_format == "csv":
        return format_csv(valid)
    raise ValueError(f"Unsupported format: {output_format}")

def main(args=None):
    pass
