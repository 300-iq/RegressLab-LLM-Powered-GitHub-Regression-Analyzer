# Java benchmark producer

`demo/` mirrors the approved public Java 21/JMH 1.37 application and producer in
[300-iq/regresslab-java-demo](https://github.com/300-iq/regresslab-java-demo).
It contains two event-query benchmarks, a correctness check, a pinned unprivileged
pull_request workflow and bounded Python orchestration/normalization tests.

`demo/demo-pr/EventStatistics.java` is the intentional aggregate-on-read example
used in public PR #1. It is actual executable code, not fabricated benchmark output.
The producer emits v2 run JSON and a v1 execution ledger per arm; original artifacts
and local runtime evidence are stored outside source control.

Execution is separate from Spring Boot. The API treats uploaded artifacts as inert
structured data and never executes them. See [producer setup](demo/README.md) and
[GitHub integration](../docs/github-integration.md) for the protocol, original archive
import, approved pipeline checks and measurement limitations.
