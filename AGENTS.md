# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Add durable project-specific notes here as they are discovered through real work.

## Demo automation and environment sharp edges

- `scripts/auto-demo.sh` runs every automatable scenario of `docs/demo-be.md` (the demo checklist is the ground truth) and prints a PASS/FAIL table; `--demo N` runs one. Its header documents all machine assumptions.
- Java comes from asdf here but no version is selected for this repo: export `ASDF_JAVA_VERSION=openjdk-17` if `java` fails with "No version is set".
- Docker runs under colima: export `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` (the demo checklist's prep section); use the standalone `docker-compose` binary.
- A host-local PostgreSQL can shadow `localhost:5432` away from the `betting-postgres` container (boot fails with `role "betting" does not exist`); pass `--spring.datasource.url=jdbc:postgresql://$(ipconfig getifaddr en0):5432/betting` to route through the colima forward. Details in the auto-demo.sh header (E1/E2).

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
