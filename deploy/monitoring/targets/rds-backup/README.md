# RDS backup target

The directory is mounted only by the optional `compose.rds-backup.prod.yml` overlay.
Prometheus reads only `*.json`, so the checked-in example does not enable scraping.

After verifying the exporter in the production Compose network, copy
`target.json.example` to `target.json` on the monitoring server. The actual file is
not committed. Remove the actual target before stopping the exporter during rollback.

See [the #174 deployment guide](../../../RDS_BACKUP_MONITORING.md).
