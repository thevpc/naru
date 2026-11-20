import unittest
from csvtool.cli import process_data

class TestCsvTool(unittest.TestCase):
    def test_csv_output(self):
        rows = [["name", "age"], ["Alice", "30"]]
        res = process_data(rows, output_format="csv")
        self.assertEqual(res, "name,age\nAlice,30")

if __name__ == "__main__":
    unittest.main()
