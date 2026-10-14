# Isolated API test fixtures

These snapshots are consumed only by automated frontend tests; application routes
never load them as API fallbacks. `synthetic-comparisons.json` is explicitly DEMO.
The GitHub snapshots contain measurements and metadata from the public
`300-iq/regresslab-java-demo` repository. Tests may mutate copies to exercise error
and uncertainty states. UUIDs are fixture identifiers, not fresh-install IDs.

No credentials, private environment files, database dumps or original artifact
archives are included. Generate new evaluation results locally rather than
checking local runtime captures into the repository.
