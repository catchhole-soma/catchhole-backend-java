"""SDK wiring test; AWS requests are intercepted locally by botocore Stubber."""

from datetime import datetime, timezone
import os
import unittest
from unittest.mock import patch

from exporter import Collector, Settings, make_rds_client


class SdkTest(unittest.TestCase):
    def test_configured_client_and_real_sdk_response_shape(self):
        try:
            from botocore.stub import Stubber
        except ImportError:
            self.skipTest("Install requirements.txt to test the pinned SDK")
        with patch.dict(os.environ, {
            "AWS_ACCESS_KEY_ID": "test-only-not-a-credential",
            "AWS_SECRET_ACCESS_KEY": "test-only-not-a-credential",
            "AWS_EC2_METADATA_DISABLED": "true",
        }, clear=True):
            settings = Settings("test-db", "ap-northeast-2")
            client = make_rds_client(settings)
            self.assertEqual(client.meta.config.connect_timeout, 3)
            self.assertEqual(client.meta.config.read_timeout, 5)
            self.assertEqual(client.meta.config.retries["total_max_attempts"], 2)
            with Stubber(client) as stub:
                stub.add_response("describe_db_instances", {"DBInstances": [{
                    "DBInstanceIdentifier": "test-db",
                    "LatestRestorableTime": datetime.fromtimestamp(700, timezone.utc),
                }]}, {"DBInstanceIdentifier": "test-db"})
                stub.add_client_error("describe_db_instances", "AccessDenied",
                                      "secret-error-message", expected_params={"DBInstanceIdentifier": "test-db"})
                collector = Collector(settings, lambda _: client, lambda: 1000)
                collector.poll_once()
                self.assertEqual(collector.snapshot().latest_restorable_timestamp_seconds, 700)
                with self.assertLogs("rds_backup_exporter", level="WARNING") as logs:
                    collector.poll_once()
                self.assertNotIn("secret-error-message", str(logs.output))
                self.assertEqual(collector.snapshot().api_success, 0)
                stub.assert_no_pending_responses()
            client.close()


if __name__ == "__main__":
    unittest.main()
