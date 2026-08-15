# Basketball Betting — Backend Training Repository

One Kotlin/Spring Boot service, built up over four branches. Each branch adds one
module's worth of capability to the branch before it, so a checkout shows exactly
what has been taught so far and nothing that has not.

| Branch | Adds | Runs |
|---|---|---|
| `demo-1` | The vertical slice: `POST /api/v1/bets` across controller, service, repository, with Odds and Risk validated concurrently | `test`, `integrationTest` |
| `demo-2` | OAuth2 Resource Server and the `@RestControllerAdvice` error contract | `+ SecurityApiTest` |
| `demo-3` | Spring Cloud Stream and Solace: an accepted bet is published as a durable event | `+ event assertions` |
| `demo-4` | The tests: TDD cycle (commented, written live), unit tests, Pact contracts | `+ contractTest` |

`main` points at `demo-4`. Everything the platform module demonstrates — the Gradle
layout, packaging, Flyway migrations, profiles, and observability — exists from
`demo-1` onwards and can be shown from any branch.

The step-by-step classroom script is [`docs/demo-be.md`](docs/demo-be.md).

## Prerequisites

- JDK 17
- A Docker engine, only for the database, the broker, and `integrationTest`

## Backing services

`compose.yaml` provides the two external systems. Both are optional: the default
profile runs entirely on in-memory adapters.

```bash
docker-compose up -d              # PostgreSQL + Solace
docker-compose up -d postgres     # database profile only
docker-compose up -d solace       # solace profile only
docker-compose ps                 # wait until both report (healthy)
docker-compose down               # state is intentionally ephemeral
```

| Service | Host port | Used by | Credentials |
|---|---|---|---|
| PostgreSQL 17 | `5432` | `database` profile | `betting` / `betting`, database `betting` |
| Solace SMF | `55555` and `15555` | `solace` profile | msgVpn `default`, user `default`, password `default` |
| Solace PubSub+ Manager | `8088` | Browsing topics and queues | `admin` / `admin` |

The manager is published on `8088` because the broker's own UI listens on `8080`
inside the container, which is the port `bootRun` takes on the host.

Solace needs roughly 1 core and 2 GiB for the tier configured here. Give the VM at
least 4 cores and 8 GiB before starting it, and allow 30–60 seconds on first start:

```bash
colima stop && colima start --cpu 4 --memory 8 --disk 60
```

`integrationTest` ignores this file entirely — it starts its own PostgreSQL through
Testcontainers.

### Colima

Colima publishes a port by binding it on macOS. If that host port is already taken,
the forward is skipped **silently**: `docker port` still shows the mapping and the
proxy is listening inside the VM, but nothing on macOS can connect. An occupied port
can also be invisible to unprivileged tools, so check with
`sudo lsof -nP -iTCP:<port>` when a port looks free but refuses connections.

`55555` is a common casualty, which is why `compose.yaml` also publishes SMF on
`15555` and `solace.java.host` defaults to it. Switch back with
`SOLACE_HOST=tcp://localhost:55555` when 55555 is free.

Testcontainers has to be pointed at Colima's socket:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Colima also establishes the host-side forward a second or two *after* the container
reports ready, so the first database connection can be refused. The integration test
handles this by retrying Flyway's initial connection; no re-run is needed.

None of this applies to Docker Desktop.

## Starting the service

Three ways to run it, in increasing order of what has to be up first.

### 1. Default — everything in memory

No Docker, no database, no broker: the repository and the event publisher are
in-memory adapters.

**The Odds and Risk services still have to be running.** They are real HTTP
boundaries on `8091` and `8092`, not in-process fakes, which is what makes the
concurrency demo honest — and what makes `POST /api/v1/bets` answer `500` if they
are missing.

```bash
# Terminal A — the two prepared downstream services
./gradlew runDemoDownstreams

# Terminal B — the API
./gradlew bootRun
```

Place a bet (on `demo-1`; no token yet):

```bash
curl -i -X POST http://localhost:8080/api/v1/bets \
  -H 'X-Customer-Id: C-100' -H 'Content-Type: application/json' \
  -d '{"gameId":"G-100","selection":"HOME","stake":100}'
```

`GET /api/v1/games/G-100` works without Terminal A — only placing a bet calls out.
Point the clients elsewhere with `TRAINING_ODDS_BASE_URL` and `TRAINING_RISK_BASE_URL`.

### 2. Real PostgreSQL and Solace

```bash
colima start --cpu 4 --memory 8 --disk 60     # skip if Colima is already running
docker-compose up -d
docker-compose ps                              # wait until both report (healthy)

# Terminal A — same as above
./gradlew runDemoDownstreams

# Terminal B — both real adapters
./gradlew bootRun --args='--spring.profiles.active=database,solace'
```

Profiles compose, so switch one adapter at a time with `--spring.profiles.active=database`
or `=solace`. The broker is only used from `demo-3` onwards.

These three startup lines confirm both real adapters are attached:

```
The following 2 profiles are active: "database", "solace"
Database: jdbc:postgresql://localhost:5432/betting (PostgreSQL 17.10)
Client-1: Connecting to host 'orig=tcp://localhost:15555, ... port=15555'
```

### 3. Tokens — required from `demo-2` onwards

```bash
eval "$(./gradlew -q generateDemoTokens)"      # sets the four variables in this shell
```

| Variable | Scope | Good for |
|---|---|---|
| `VALID_NO_SCOPE_TOKEN` | none | demonstrating `403` |
| `VALID_GAMES_READ_TOKEN` | `games:read` | `GET /api/v1/games/{id}` |
| `VALID_BETS_WRITE_TOKEN` | `bets:write` | `POST /api/v1/bets` |
| `VALID_MOBILE_TOKEN` | both | the frontend |

```bash
curl -i -X POST http://localhost:8080/api/v1/bets \
  -H "Authorization: Bearer $VALID_BETS_WRITE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"gameId":"G-100","selection":"HOME","stake":100}'
```

Tokens are signed with the local demo secret in `SecurityConfig` and last 24 hours.
They are classroom credentials, not something to reuse anywhere else.

### An API collection you can import

[`docs/demo.json`](docs/demo.json) is a Postman v2.1 collection covering every endpoint
this service exposes. **Postman and Bruno both import it directly** — in Bruno, use
*Import → Postman Collection*.

| Request | Needs |
|---|---|
| `POST /api/v1/bets` | the downstream stubs; a token from `demo-2` onwards |
| `GET /api/v1/games/:gameId` | a token from `demo-2` onwards |
| `GET /actuator/health` | nothing |
| `GET /actuator/metrics` | nothing |
| `GET /actuator/prometheus` | nothing |
| `GET /v3/api-docs` | nothing |

The two API requests send `Authorization: Bearer {{token}}`. Set the collection's
`token` variable to one of the values printed by `generateDemoTokens`
(`VALID_MOBILE_TOKEN` carries both scopes, so it covers the whole collection). On
`demo-1` the header is simply ignored — there is no security yet.

## Tests

```bash
./gradlew test                      # unit and API tests, no Docker
./gradlew integrationTest           # PostgreSQL boundary via Testcontainers
./gradlew contractTest              # Pact consumers and provider verification (demo-4)
```

Add `--rerun-tasks` during training so Gradle prints outcomes instead of reusing a
cached result.

## Packaging

```bash
./gradlew bootJar     # executable, dependency-inclusive: build/libs/*-SNAPSHOT.jar
./gradlew thinJar     # application classes only: build/libs/basketball-betting.jar
./gradlew slimDist    # app/ + lib/ + bin/ under build/slim/basketball-betting
build/slim/basketball-betting/bin/basketball-betting
```

## Observation points

| URL | Shows |
|---|---|
| `http://localhost:8080/swagger-ui.html` | The API contract, with Authorize for the token |
| `http://localhost:8080/actuator/health` | Liveness |
| `http://localhost:8080/actuator/metrics/http.server.requests` | Request metrics, drillable by tag |
| `http://localhost:8080/actuator/prometheus` | Every metric's current value in one response |
| `http://localhost:8088` | Solace topics, queues, and spooled messages |

Every log line carries a trace identifier in a fixed column, and the response echoes
it in `X-Trace-Id`, so one request can be followed from the API to the consumer.

## Frontend

`frontend/` holds the Expo client that consumes this API. It is out of scope for the
backend course and is kept only so the CORS configuration has a real caller.
