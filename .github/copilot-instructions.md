# Copilot instructions for this repository

A Kotlin / Spring Boot 4 betting API used as training material. Suggestions are read
aloud in a classroom, so they have to look like the code already here.

## Architecture

Ports and adapters, selected by Spring profile. An interface lives next to its
implementations; the service never knows which one it is talking to.

| Profile | Selects |
|---|---|
| `database` / `!database` | `JdbcBetRepository`, `JdbcGameRepository` / the in-memory pair |
| `solace` / `!solace` | `SolaceBetPlacedPublisher` / `InMemoryBetPlacedPublisher` |
| `test` / `!test` | deterministic Odds and Risk clients / the real HTTP clients |

Packages are named after the role they play, not the technology:

`controller` · `service` · `repository` · `domain` · `enum` · `dto` · `client` ·
`messaging` · `configuration` · `exception`

## Conventions to follow

- **Kotlin**: immutable `data class` domain models, `val` over `var`, constructor
  injection only — no field injection, no `@Autowired` on properties.
- **DTOs are separate from the domain.** `PlaceBetRequest` / `BetResponse` are wire
  contracts; `Bet` and `Game` are the domain. Never return a domain object from a
  controller.
- **Validation is declared on the DTO** with `@field:NotBlank`, `@field:DecimalMin`,
  never written as `if` checks in the service.
- **Identity comes from the token** via `@AuthenticationPrincipal Jwt`, never from the
  request body or a client-supplied header.
- **Errors**: throw a domain exception from `exception/`, and map it in
  `ApiExceptionHandler`. Every error response is an `ApiError` with a stable `code`.
- **Time** comes from the injected `Clock`, never `Instant.now()` directly.
- **Concurrency**: independent remote calls go in `coroutineScope { async { … } }` and
  are awaited together. Suspend functions all the way down; never block a thread.
- **Correlation** travels in the MDC and in message headers, never in an event payload.
- **Schema changes are new Flyway files** under `db/migration`. Never edit a migration
  that has already run.

## Adding a route

A new endpoint usually needs all of these — a suggestion that stops at the controller
is incomplete:

1. the controller method, with `@Operation` and `@ApiResponses`
2. a DTO for the response
3. a service method
4. a repository method on the port **and both adapters**
5. **an authorization rule in `SecurityConfig`** — the chain ends in
   `anyRequest().permitAll()`, so a route with no rule is open to anyone
6. tests: an API test for the contract, a unit test for the rule

## Testing

- **Unit**: Kotest `FunSpec` + MockK. Stub collaborators; assert results *and*
  interactions with `coVerify(exactly = n)`. Reset shared mocks in `beforeTest`.
- **API**: `@SpringBootTest` + `@AutoConfigureMockMvc`. Suspend handlers need
  `performSuspending` from `MockMvcCoroutineSupport`, not plain `perform`.
- **Integration**: `integrationTest` source set, Testcontainers, real PostgreSQL.
- **Contract**: `contractTest` source set, Pact. Consumer test writes the pact;
  provider test replays it against the running application.

## Comments

Explain *why*, never *what*. If a name says it, do not add a comment. No commented-out
code, no `// TODO` without a reason, no line-number references.
