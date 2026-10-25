import pathlib
import re
import unittest


class NotificationChannelTest(unittest.TestCase):
    def test_widget_notification_uses_registered_channel(self):
        source = (pathlib.Path(__file__).parents[1] / "app/src/main/java/com/hmt/meteo/OverlayService.kt").read_text()
        registered = re.search(r'NotificationChannel\((\w+),', source)
        used = re.search(r'Notification\.Builder\(this, (\w+)\)', source)
        self.assertIsNotNone(registered)
        self.assertIsNotNone(used)
        self.assertEqual(registered.group(1), used.group(1))
        self.assertRegex(source, rf'const val {registered.group(1)} = "w"')


if __name__ == "__main__":
    unittest.main()
