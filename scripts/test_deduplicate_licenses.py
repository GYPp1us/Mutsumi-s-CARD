"""重复原文必须复用，归属和版权差异必须保留。"""
import unittest
from deduplicate_licenses import compact

class LicenseTests(unittest.TestCase):
    def test_shared_body_preserves_each_attribution(self):
        source = "说明\n\n## first 1.0\n来源：one\n许可证：MIT\n\n### LICENSE\n\nSame copyright and terms\n\n## second 2.0\n来源：two\n许可证：MIT\n\n### LICENSE-MIT\n\nSame copyright and terms\n"
        result = compact(source)
        self.assertEqual(result.count("Same copyright and terms"), 1)
        self.assertIn("## first 1.0", result)
        self.assertIn("来源：two", result)
        self.assertEqual(result.count("原文复用："), 2)
        self.assertEqual(compact(result), result)

    def test_distinct_copyrights_are_not_merged(self):
        source = "## first 1.0\n来源：one\n许可证：MIT\n\n### LICENSE\n\nCopyright A\n\n## second 1.0\n来源：two\n许可证：MIT\n\n### LICENSE\n\nCopyright B\n"
        result = compact(source)
        self.assertIn("Copyright A", result)
        self.assertIn("Copyright B", result)
        self.assertEqual(result.count("### 原文 "), 2)

if __name__ == "__main__":
    unittest.main()
