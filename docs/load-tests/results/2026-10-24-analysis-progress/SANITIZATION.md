# Evidence scope

Local synthetic data only. Absolute measurement/home paths are replaced with placeholders. Private credentials, browser storage, cookie/token/header values, application logs, PostgreSQL logs and raw test traces are excluded. SQL files retain synthetic UUID bindings and EXPLAIN plans. Original artifacts and raw files remain outside the repository.

Fixture hashes cover the named JSON/payload/metadata columns; they are not a checksum of every database value. The separate before/after readbacks preserve job statuses, run/latch metadata and target memberships. Initial navigation, quiescent polling and the active case are separate samples.
