# Runtime and dependency versions

Direct npm dependencies are pinned in workspace manifests and package-lock.json.
Backend dependencies use Spring Boot's BOM and backend/gradle.lockfile. The Gradle
wrapper includes a distribution checksum. Image digests are pinned in Compose and
the backend Dockerfile.

| Component | Version |
| --- | --- |
| Node.js | 24.21.0 LTS |
| Java | 21 |
| Next.js | 16.4.0 |
| React / React DOM | 19.3.0 |
| TypeScript | 5.9.3 |
| Tailwind / PostCSS plugin | 4.3.3 |
| Recharts | 3.10.1 |
| Zod | 4.3.6 |
| ESLint / Next config | 10.12.0 / 16.4.0 |
| Vitest | 4.1.11 |
| Playwright | 1.64.0 |
| Ajv / formats | 8.20.0 / 3.0.1 |
| Spring Boot | 3.5.16 |
| Gradle | 8.14 |
| PostgreSQL | 17.9 |
| Flyway | 11.7.2 |
| PostgreSQL JDBC | 42.7.11 |
| Native PostgreSQL test launcher | 2.2.2; binary BOM 17.9.0 |
| JMH | 1.37 |
| Maven compiler / shade plugins | 3.14.1 / 3.6.1 |

Next.js scripts use Webpack. Spring Boot manages Spring Framework, Jackson,
validation, logging and JUnit versions; avoid independent overrides without checking
compatibility. Update dependency and image pins together with their lockfiles.

## Benchmark environment

The public producer pins checkout, setup-java and upload-artifact Actions to commit
SHAs in benchmarks/demo/.github/workflows/benchmark.yml. The JDK 21 patch version is
selected by setup-java and recorded in each run's environment metadata. Recorded
configuration/environment hashes determine whether runs can be compared.

The producer uses Python 3's standard library and Maven 3.9. Test fixtures and local
evaluation results do not attest the runtime conditions of a new GitHub workflow.

## Local inference

Ollama is installed separately. The tested setup uses Ollama 0.34.0 and qwen3:4b,
model digest `359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7`.
Each report records its actual model digest and inference configuration; see
[LLM integration](llm-integration.md). The application never downloads a model.

The optional backend image uses the official multiarch Java 21 JRE digest recorded
in backend/Dockerfile. Native macOS execution is tested; Docker image build and
Desktop routing remain unverified. See [local setup](local-setup.md).
