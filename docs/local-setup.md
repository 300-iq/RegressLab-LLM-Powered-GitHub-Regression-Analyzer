# Local setup

Use Java 21, Node 24 LTS and real PostgreSQL 17. Ollama is optional for browsing;
AI generation requires an installed local model, default `qwen3:4b`.
Run commands from the repository root.

## Private configuration

```sh
cp .env.example .env
chmod 600 .env
printf '\nPOSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)" >> .env
printf '\nREGRESSLAB_INGESTION_TOKEN=%s\n' "$(openssl rand -hex 32)" >> .env
set -a
source .env
set +a
```

`.env` is ignored. Never commit it, print its values, or put credentials in
`NEXT_PUBLIC_*`. The empty fields in `.env.example` are intentional; there is no
shared application password. Use the same private administrative token in backend
and protected frontend terminals.

## PostgreSQL and build

```sh
docker compose --env-file .env -f infra/compose.yaml up -d postgres
npm ci
npm run check
(cd backend && ./gradlew clean test bootJar)
```

Alternatively use native PostgreSQL 17 with an existing local role/database. Set
POSTGRES_DB/USER/PORT/PASSWORD in your private configuration to that instance.
Docker is not required for backend integration tests; they launch native real
PostgreSQL binaries. No H2, automatic seed or mock API fallback exists.

## Native Spring and frontend

In the backend terminal:

```sh
export DATABASE_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${POSTGRES_DB}"
export DATABASE_USER="$POSTGRES_USER"
export DATABASE_PASSWORD="$POSTGRES_PASSWORD"
java -jar backend/build/libs/regresslab-api.jar
```

Check `curl --fail http://127.0.0.1:8080/actuator/health/readiness`.
In a second terminal:

```sh
export REGRESSLAB_API_URL=http://127.0.0.1:8080
npm run start --workspace frontend
```

Open `http://127.0.0.1:3000`. An empty dashboard is expected until artifacts are
imported. Standard startup is read-only. Stop the identified JVM/frontend before
rebuilding the JAR/shared `.next`, or launch the JVM from an immutable copied JAR.

## Enable authorized local import and AI

Stop Spring, load the same private `.env`, and add these backend-only settings
before restarting the JAR:

```sh
export SPRING_PROFILES_ACTIVE=local-ingestion
export SERVER_ADDRESS=127.0.0.1
export REGRESSLAB_REPOSITORY_GITHUB_ID=1417739588
export REGRESSLAB_REPOSITORY_FULL_NAME=300-iq/regresslab-java-demo
export REGRESSLAB_GITHUB_IMPORT_ENABLED=true
export REGRESSLAB_AI_ENABLED=true
export REGRESSLAB_OLLAMA_URL=http://127.0.0.1:11434
export REGRESSLAB_OLLAMA_MODEL=qwen3:4b
export REGRESSLAB_OLLAMA_CONTEXT_TOKENS=16384
export REGRESSLAB_OLLAMA_TIMEOUT_SECONDS=120
java -jar backend/build/libs/regresslab-api.jar
```

Generation/import additionally require the private token, direct loopback peer,
allowlist and request/rate limits. They reject forwarded requests. GitHub read
credentials, when required for rate limits, stay server-side in
REGRESSLAB_GITHUB_READ_TOKEN. [Import genuine original archives](github-integration.md);
fixtures cannot replace REAL/GITHUB_METADATA evidence for AI generation.

Before enabling AI, verify `ollama list` and
`curl --fail http://127.0.0.1:11434/api/tags`. The application never downloads a model.
For a fresh CLI-managed server, use conservative local settings:

```sh
OLLAMA_HOST=127.0.0.1:11434 OLLAMA_NUM_PARALLEL=1 OLLAMA_MAX_LOADED_MODELS=1 \
OLLAMA_MAX_QUEUE=1 OLLAMA_NO_CLOUD=1 ollama serve
```

For the macOS app, set equivalent values with `launchctl setenv`, then quit/reopen
Ollama. Do not start a second daemon over it. Keep Ollama native/private; never bind
all interfaces, publish port 11434, add browser CORS access or tunnel it.
[Official Ollama configuration](https://docs.ollama.com/faq).

In the frontend terminal, load the same `.env`, set REGRESSLAB_API_URL and use:

```sh
npm run ai:local --workspace frontend
```

The opt-in launcher binds loopback and retains the bearer only on the server.
Select an eligible PR comparison/stored analysis and press **Investigate with AI**.
POST returns 202/job ID; read-only polling shows progress. Reload resumes the status;
leaving the page does not cancel accepted work. Reports persist, failures preserve
older reports, and unchanged authorized evidence/model/configuration reuses them.

Context range is 8192–16384; timeout is 1–300 seconds. Smaller contexts reduce the
prompt-byte budget and can reject evidence. One job runs per database; the app sends
keep_alive:0. Other daemon clients and total peak RAM remain outside its control.

## Docker backend with native macOS Ollama

Normal bridge localhost is the container. The optional backend uses Docker Desktop
4.34+ **host networking**, enabled in Desktop settings, while Ollama remains native
for Metal. [Docker host networking](https://docs.docker.com/engine/network/drivers/host/).
After the host bootJar build:

```sh
docker compose --env-file .env -f infra/compose.yaml \
  -f infra/compose.backend-host.yaml up --build -d
```

Set AI/import opt-ins in private configuration if needed. The overlay uses a pinned
non-root read-only runtime, a memory cap and loopback PostgreSQL/Ollama. The Docker
build context contains only the Dockerfile and compiled JAR, not `.env` or source
captures. No Ollama port is published.

Docker image build, Desktop routing and peer behavior were not verified on the
native development host. Enhanced Container Isolation or unsupported host networking
may prevent it. Do not widen bindings/peer checks or change to an arbitrary provider
host to bypass this boundary; use native Spring if it cannot be maintained.

## Browser tests

Build first and point both preview/test process at the same API. For an empty real DB:

```sh
REGRESSLAB_API_URL=http://127.0.0.1:8080 E2E_REQUIRE_API=true npm run test:e2e
```

E2E_EXPECT_REAL=true checks imported public records; E2E_EXPECT_DEMO=true checks
explicit synthetic fixture records. E2E_AI_GENERATION=true opts into an eligible
local job; AI_E2E_PR_NUMBER selects its PR. Do not enable real-data assertions on
a database lacking the historical records they require. Browser tests never load
fixtures or intercept production responses automatically.
