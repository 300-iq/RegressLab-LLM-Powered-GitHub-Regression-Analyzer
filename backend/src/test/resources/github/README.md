# GitHub archive fixtures

These two small data-only ZIPs are required by archive validation and PostgreSQL
ingestion tests. They contain the original public demonstration run.json and
execution.json entries. Tests preserve their exact bytes to verify archive digests,
replay behavior and rejection of modified content.

They are not runtime seed data, credentials or executable uploads. The specific
files are included in Git; generated artifacts and other archives remain ignored.
