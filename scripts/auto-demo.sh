#!/usr/bin/env bash
# =============================================================================
# auto-demo.sh - run the automatable demos from docs/demo-be.md end to end and
# report PASS/FAIL per scenario against the doc's expected outputs.
#
# Usage:
#   scripts/auto-demo.sh                 # run every automatable demo (1-9)
#   scripts/auto-demo.sh --demo 2        # run only demo 2
#   scripts/auto-demo.sh --demo 4.1      # run only the TDD cycle of demo 4
#   scripts/auto-demo.sh --demo 4        # run all of demo 4 (4.1-4.4)
#   scripts/auto-demo.sh --demo 3 --demo 9
#   scripts/auto-demo.sh --pactflow ...  # also execute 4.4 (default: SKIP)
#
# What it does NOT do: push, commit demo-state changes, delete user data, or
# touch PactFlow unless --pactflow is given AND PACT_BROKER_BASE_URL /
# PACT_BROKER_TOKEN are set. Demo 4.4 is otherwise reported as SKIP.
#
# ------------------------- machine assumptions -------------------------------
# Verified on the instructor's machine (see the pre-flight report
# data/basketball-demo-verify/report.md in the firstmate home):
#
#   * macOS (Apple Silicon), JDK 17, Gradle wrapper. Cold first build ~3 min;
#     Solace first start ~40 s. Both are expected, not failures.
#   * Docker runs in colima. If DOCKER_HOST is unset and
#     ~/.colima/default/docker.sock exists, this script exports
#         DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
#         TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
#     exactly as docs/demo-be.md's prep section prescribes.
#   * `docker-compose` is used when the standalone binary exists (this machine),
#     otherwise the script falls back to `docker compose`.
#
# E1 - host PostgreSQL shadows localhost:5432 (breaks database-profile demos):
#   the colima port forward for betting-postgres binds *:5432, but a host-local
#   postgres bound to 127.0.0.1:5432 wins on loopback, so the app's default
#   jdbc:postgresql://localhost:5432/betting lands on the wrong server
#   ("FATAL: role \"betting\" does not exist"). This script detects a host
#   postgres listener via lsof and then passes
#       --spring.datasource.url=jdbc:postgresql://$(ipconfig getifaddr en0):5432/betting
#   as a Spring Boot command-line argument (CLI args beat both the properties
#   default and the Gradle daemon's stale environment). The same workaround is
#   applied retroactively if a database-profile boot fails with that exact
#   error. If the LAN-IP route also fails, the demo is FAILed with an
#   actionable message (remap the compose host port, or stop the host postgres).
#
# E2 - betting-solace OOM on a busy 4 GiB colima VM:
#   the script warns when the Docker VM reports < 6 GiB total memory, and if
#   the solace container dies during startup it inspects State.OOMKilled and
#   fails demo 3 with an actionable message (stop unrelated containers, or
#   recreate colima with more memory: `colima stop && colima start --memory 8`).
#
# Branch handling: demos 1-4 run on their documented branches (demo-1..demo-4),
# demos 5-9 on demo-4. The script re-executes itself from a temp copy so that
# `git checkout` of a branch without scripts/ cannot cut the running script
# from under itself. On exit (normal or interrupted) it restores the doc's
# clean-state checklist: original branch checked out, `git checkout -- src/`,
# V4 back to .sql.disabled, flyway_schema_history V4 row and demo index dropped
# if they were applied, all background processes stopped.
# =============================================================================

set -uo pipefail

# --- re-exec from a temp copy so git checkouts cannot remove the running script
if [[ -z "${AUTO_DEMO_REEXEC:-}" ]]; then
	SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd -P)/$(basename "$0")"
	REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
	TMP_COPY="$(mktemp -d -t auto-demo)/auto-demo.sh"
	cp "$SCRIPT_PATH" "$TMP_COPY"
	AUTO_DEMO_REEXEC=1 AUTO_DEMO_REPO="$REPO_ROOT" exec bash "$TMP_COPY" "$@"
fi

REPO="${AUTO_DEMO_REPO:?}"
cd "$REPO" || exit 2

# ---------------------------------------------------------------------------
# configuration
# ---------------------------------------------------------------------------
BASE_URL="http://localhost:8080"
SEMP_URL="http://localhost:8088/SEMP/v2/monitor/msgVpns/default/queues"
SEMP_AUTH="admin:admin"
APP_CLASS="BasketballBettingDemoApplicationKt"
DOWNSTREAM_CLASS="DemoDownstreamServicesKt"
BOOT_TIMEOUT=420        # first cold Gradle build takes ~3 min
CONTAINER_TIMEOUT=180   # solace needs ~40 s once the image is present
CONSUMER_TIMEOUT=60     # async event round trip through the broker

RUN_DIR="$REPO/build/auto-demo"
rm -rf "$RUN_DIR"
mkdir -p "$RUN_DIR"

ORIG_REF="$(git rev-parse --abbrev-ref HEAD)"
[[ "$ORIG_REF" == "HEAD" ]] && ORIG_REF="$(git rev-parse HEAD)"

# ---------------------------------------------------------------------------
# state
# ---------------------------------------------------------------------------
declare -a RESULTS=()
APP_PID="" APP_LOG="" APP_KEY=""
DOWNSTREAM_PID="" DOWNSTREAM_LOG="$RUN_DIR/downstreams.log"
SLIM_PID="" SLIM_LOG=""
TOKENS_LOADED=0
DB_URL_ARG=""
DB_ARGS=()
PG_WORKAROUND=0
FLYWAY_V4_APPLIED=0
COMPOSE=""
PACTFLOW=0

# ---------------------------------------------------------------------------
# small helpers
# ---------------------------------------------------------------------------
log()  { printf '%s\n' "$*"; }
info() { printf '  %s\n' "$*"; }
warn() { printf '  WARNING: %s\n' "$*" >&2; }

record() { # scenario, PASS|FAIL|SKIP, detail
	RESULTS+=("$1|$2|$3")
	case "$2" in
		PASS) printf '[PASS] %-28s %s\n' "$1" "$3" ;;
		FAIL) printf '[FAIL] %-28s %s\n' "$1" "$3" ;;
		SKIP) printf '[SKIP] %-28s %s\n' "$1" "$3" ;;
	esac
}

port_open() { (echo >"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; }

wait_port() { # port, timeout
	local port=$1 timeout=$2 elapsed=0
	while (( elapsed < timeout )); do
		port_open "$port" && return 0
		sleep 2; elapsed=$((elapsed + 2))
	done
	return 1
}

wait_log() { # file, extended-regex, timeout -> 0 found / 1 not found
	local file=$1 pattern=$2 timeout=$3 elapsed=0
	while (( elapsed < timeout )); do
		[[ -f $file ]] && grep -Eq "$pattern" "$file" 2>/dev/null && return 0
		sleep 2; elapsed=$((elapsed + 2))
	done
	return 1
}

# like wait_log for the Spring Boot startup banner, but fails fast when the
# process dies or Spring reports a startup failure (saves the full timeout)
wait_started() { # file, pid, timeout
	local file=$1 pid=$2 timeout=$3 elapsed=0
	while (( elapsed < timeout )); do
		grep -Eq 'Started .*Application in ' "$file" 2>/dev/null && return 0
		grep -q 'APPLICATION FAILED TO START' "$file" 2>/dev/null && return 1
		if ! kill -0 "$pid" 2>/dev/null; then
			grep -Eq 'Started .*Application in ' "$file" 2>/dev/null
			return $?
		fi
		sleep 2; elapsed=$((elapsed + 2))
	done
	return 1
}

json_eval() { # python expression over variable d (JSON on stdin)
	python3 -c 'import sys,json
try:
    d = json.load(sys.stdin)
except Exception:
    print(""); sys.exit(0)
print('"$1"')'
}

# ---------------------------------------------------------------------------
# process lifecycle
# ---------------------------------------------------------------------------
stop_pid_tree() { # pid, pkill -f marker
	local pid=$1 marker=${2:-}
	[[ -n $pid ]] && kill "$pid" >/dev/null 2>&1
	[[ -n $marker ]] && pkill -f "$marker" >/dev/null 2>&1
	[[ -n $pid ]] && wait "$pid" >/dev/null 2>&1
	return 0
}

stop_app() {
	if [[ -n $APP_PID ]]; then
		info "stopping app (pid $APP_PID)"
		stop_pid_tree "$APP_PID" "$APP_CLASS"
	fi
	APP_PID=""; APP_KEY=""
}

stop_downstreams() {
	if [[ -n $DOWNSTREAM_PID ]]; then
		info "stopping downstream stubs (pid $DOWNSTREAM_PID)"
		stop_pid_tree "$DOWNSTREAM_PID" "$DOWNSTREAM_CLASS"
	fi
	DOWNSTREAM_PID=""
}

stop_slim() {
	if [[ -n $SLIM_PID ]]; then
		info "stopping slim app (pid $SLIM_PID)"
		stop_pid_tree "$SLIM_PID" "app/basketball-betting.jar"
	fi
	SLIM_PID=""
}

# start_app <key> <logfile> [extra spring args...]
# key identifies the configuration so ensure_app can reuse a running instance.
start_app() {
	local key=$1 logfile=$2; shift 2
	stop_app
	if port_open 8080; then
		warn "port 8080 is already in use by a foreign process; stop it before running the demos"
		return 1
	fi
	info "starting app ($key) - cold builds take minutes, this is expected"
	: >"$logfile"
	./gradlew bootRun ${1:+--args="$*"} >"$logfile" 2>&1 &
	APP_PID=$! APP_LOG="$logfile" APP_KEY="$key"
	if wait_started "$logfile" "$APP_PID" "$BOOT_TIMEOUT"; then
		info "app is up ($key)"
		return 0
	fi
	return 1
}

# ensure_app <key> <logfile> [extra spring args...]: no-op if already running
ensure_app() {
	local key=$1 logfile=$2; shift 2
	if [[ $APP_KEY == "$key" && -n $APP_PID ]] && kill -0 "$APP_PID" 2>/dev/null; then
		return 0
	fi
	start_app "$key" "$logfile" "$@"
	local rc=$?
	if (( rc != 0 )) && grep -q 'role "betting" does not exist' "$logfile" 2>/dev/null \
		&& (( PG_WORKAROUND == 0 )); then
		# E1, discovered the hard way: localhost:5432 is a foreign postgres.
		apply_pg_workaround
		info "retrying boot with the E1 LAN-IP workaround"
		start_app "$key" "$logfile" "$@" "$DB_URL_ARG"
		rc=$?
	fi
	if (( rc != 0 )); then
		warn "app failed to start ($key); last log lines:"
		tail -15 "$logfile" >&2 2>/dev/null
	fi
	return "$rc"
}

ensure_downstreams() {
	if port_open 8091 && port_open 8092; then
		info "downstream stubs already listening on 8091/8092"
		return 0
	fi
	info "starting Odds/Risk downstream stubs (runDemoDownstreams)"
	: >"$DOWNSTREAM_LOG"
	./gradlew runDemoDownstreams >"$DOWNSTREAM_LOG" 2>&1 &
	DOWNSTREAM_PID=$!
	if wait_port 8091 "$BOOT_TIMEOUT" && wait_port 8092 60; then
		info "downstream stubs are up"
		return 0
	fi
	warn "downstream stubs failed to start; last log lines:"
	tail -10 "$DOWNSTREAM_LOG" >&2 2>/dev/null
	return 1
}

load_tokens() {
	(( TOKENS_LOADED == 1 )) && return 0
	info "generating demo tokens (generateDemoTokens)"
	local out
	out="$(./gradlew -q generateDemoTokens 2>"$RUN_DIR/tokens.err")" || {
		warn "generateDemoTokens failed:"; tail -5 "$RUN_DIR/tokens.err" >&2
		return 1
	}
	eval "$out"
	if [[ -z ${VALID_BETS_WRITE_TOKEN:-} || -z ${VALID_GAMES_READ_TOKEN:-} || -z ${VALID_NO_SCOPE_TOKEN:-} ]]; then
		warn "generateDemoTokens did not produce the expected variables"
		return 1
	fi
	TOKENS_LOADED=1
}

# ---------------------------------------------------------------------------
# environment (E1 / E2 / colima / compose)
# ---------------------------------------------------------------------------
setup_docker_env() {
	if [[ -z ${DOCKER_HOST:-} && -S "$HOME/.colima/default/docker.sock" ]]; then
		export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
		export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
		info "colima detected: exported DOCKER_HOST and TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE"
	fi
	if command -v docker-compose >/dev/null 2>&1; then
		COMPOSE="docker-compose"
	elif docker compose version >/dev/null 2>&1; then
		COMPOSE="docker compose"
	else
		COMPOSE=""
		return 1
	fi
	return 0
}

detect_pg_shadow() { # 0 = a host postgres owns a loopback 5432 listener (E1)
	command -v lsof >/dev/null 2>&1 || return 1
	lsof -nP -iTCP:5432 -sTCP:LISTEN 2>/dev/null \
		| awk 'NR > 1 && $1 == "postgres" { found = 1 } END { exit(found ? 0 : 1) }'
}

apply_pg_workaround() {
	local lan_ip
	lan_ip="$(ipconfig getifaddr en0 2>/dev/null)"
	if [[ -z $lan_ip ]]; then
		warn "E1 detected but 'ipconfig getifaddr en0' returned nothing; cannot build the LAN-IP workaround"
		return 1
	fi
	PG_WORKAROUND=1
	DB_URL_ARG="--spring.datasource.url=jdbc:postgresql://$lan_ip:5432/betting"
	DB_ARGS=("$DB_URL_ARG")
	warn "E1: a host PostgreSQL shadows localhost:5432 - database demos will use $DB_URL_ARG"
	warn "E1: permanent fixes: remap the compose host port, or stop the host postgres for class day"
	return 0
}

e2_memory_check() {
	local mem
	mem="$(docker info --format '{{.MemTotal}}' 2>/dev/null)" || return 0
	[[ -z $mem || $mem == "0" ]] && return 0
	if (( mem < 6 * 1024 * 1024 * 1024 )); then
		local gib n
		gib="$(awk -v m="$mem" 'BEGIN { printf "%.1f", m/1024/1024/1024 }')"
		n="$(docker ps -q 2>/dev/null | wc -l | tr -d ' ')"
		warn "E2: Docker VM has only ${gib} GiB RAM with $n containers running."
		warn "E2: Solace needs ~2 GiB free; if betting-solace dies (OOMKilled), stop unrelated containers"
		warn "E2: or give colima more memory: colima stop && colima start --memory 8"
	fi
}

# ensure_container <service> <container_name> <timeout>
ensure_container() {
	local svc=$1 name=$2 timeout=$3
	[[ -n $COMPOSE ]] || { warn "no docker-compose / docker compose available"; return 1; }
	info "starting container $name ($COMPOSE up -d $svc)"
	# shellcheck disable=SC2086 # COMPOSE is intentionally two words in the fallback
	$COMPOSE up -d "$svc" >"$RUN_DIR/compose-$svc.log" 2>&1 || {
		warn "compose up failed for $svc:"; tail -5 "$RUN_DIR/compose-$svc.log" >&2
		return 1
	}
	local elapsed=0 status
	while (( elapsed < timeout )); do
		status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null)"
		if [[ $status == "healthy" ]]; then
			info "$name is healthy"
			return 0
		fi
		if [[ $status == "none" ]]; then
			docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null | grep -q true && {
				info "$name is running (no healthcheck)"; return 0; }
		fi
		if ! docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null | grep -q true; then
			local oom
			oom="$(docker inspect -f '{{.State.OOMKilled}}' "$name" 2>/dev/null)"
			if [[ $oom == "true" ]]; then
				warn "E2: $name was OOM-killed. Stop unrelated containers or give colima more"
				warn "E2: memory (colima stop && colima start --memory 8), then re-run."
			else
				warn "$name exited during startup; recent container logs:"
				docker logs --tail 10 "$name" >&2 2>/dev/null
			fi
			return 1
		fi
		sleep 5; elapsed=$((elapsed + 5))
	done
	warn "$name did not become healthy within ${timeout}s"
	return 1
}

# ---------------------------------------------------------------------------
# git hygiene
# ---------------------------------------------------------------------------
CURRENT_BRANCH=""
need_branch() {
	local branch=$1
	[[ $CURRENT_BRANCH == "$branch" ]] && return 0
	stop_app; stop_downstreams
	# demo scratch lives only under src/; restore before switching
	git checkout -- src/ >/dev/null 2>&1
	info "git checkout $branch"
	if ! git checkout "$branch" >"$RUN_DIR/git.log" 2>&1; then
		warn "git checkout $branch failed:"; tail -5 "$RUN_DIR/git.log" >&2
		return 1
	fi
	CURRENT_BRANCH="$branch"
	# a branch switch changes sources; drop any loaded token set (cheap to redo)
	return 0
}

v4_sql="src/main/resources/db/migration/V4__index_bet_by_game.sql"
v4_disabled="src/main/resources/db/migration/V4__index_bet_by_game.sql.disabled"

# undo the demo-7 V4 rename no matter which state git checkout left behind:
# both present -> the .sql is the unmodified renamed original, drop it;
# only .sql present -> rename it back
restore_v4() {
	if [[ -f $v4_sql ]]; then
		if [[ -f $v4_disabled ]]; then rm -f "$v4_sql"; else mv "$v4_sql" "$v4_disabled"; fi
	fi
}

restore_repo_state() {
	stop_app; stop_downstreams; stop_slim
	cd "$REPO" || return 0
	# V4 rename back before git checkout so .sql.disabled is not left untracked
	restore_v4
	git checkout -- src/ >/dev/null 2>&1
	if (( FLYWAY_V4_APPLIED == 1 )); then
		docker exec betting-postgres psql -U betting -d betting -c \
			"delete from flyway_schema_history where version='4'; drop index if exists idx_bet_game_id;" \
			>/dev/null 2>&1
		FLYWAY_V4_APPLIED=0
	fi
	if [[ $(git rev-parse --abbrev-ref HEAD) != "$ORIG_REF" ]]; then
		git checkout "$ORIG_REF" >/dev/null 2>&1 \
			|| git checkout --detach "$ORIG_REF" >/dev/null 2>&1
	fi
}

TABLE_PRINTED=0
print_table() {
	TABLE_PRINTED=1
	printf '\n================ auto-demo results ================\n'
	printf '%-30s %-6s %s\n' "SCENARIO" "RESULT" "DETAIL"
	printf '%s\n' "--------------------------------------------------"
	local line fails=0
	for line in ${RESULTS[@]+"${RESULTS[@]}"}; do
		printf '%-30s %-6s %s\n' "${line%%|*}" "$(echo "$line" | cut -d'|' -f2)" "${line##*|}"
		[[ $line == *"|FAIL|"* ]] && fails=$((fails + 1))
	done
	printf '%s\n' "--------------------------------------------------"
	if (( fails == 0 )); then
		log "BOTTOM LINE: runnable in class as-is (all executed scenarios PASS; 4.4 SKIP unless --pactflow)."
	else
		log "BOTTOM LINE: needs fixing first - $fails scenario(s) FAILed; see details above."
	fi
	log "logs: $RUN_DIR"
}

on_exit() {
	local rc=$?
	restore_repo_state
	if (( TABLE_PRINTED == 0 )); then
		warn "run interrupted before the results table; partial results:"
		print_table
	fi
	exit "$rc"
}
trap on_exit EXIT

# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------
# place_bet_insecure: demo-1 style, identity via X-Customer-Id
place_bet_insecure() { # gameId selection stake -> "code<TAB>body"
	local body
	body="$(curl -s -o - -w $'\t%{http_code}' -X POST "$BASE_URL/api/v1/bets" \
		-H 'X-Customer-Id: C-100' -H 'Content-Type: application/json' \
		-d "{\"gameId\":\"$1\",\"selection\":\"$2\",\"stake\":$3}")"
	printf '%s' "$body"
}

# place_bet_secured: demo-2+ style, bearer token; optional trace id as $4
place_bet_secured() { # gameId selection stake [traceId] [header_dump_file]
	local -a extra=()
	[[ -n ${4:-} ]] && extra+=(-H "X-Trace-Id: $4")
	[[ -n ${5:-} ]] && extra+=(-D "$5")
	curl -s -o - -w $'\t%{http_code}' -X POST "$BASE_URL/api/v1/bets" \
		-H "Authorization: Bearer $VALID_BETS_WRITE_TOKEN" \
		-H 'Content-Type: application/json' ${extra[@]+"${extra[@]}"} \
		-d "{\"gameId\":\"$1\",\"selection\":\"$2\",\"stake\":$3}"
}

split_code_body() { # sets CODE / BODY from a "body<TAB>code" string on $1
	CODE="${1##*$'\t'}"
	BODY="${1%$'\t'*}"
}

# ---------------------------------------------------------------------------
# Demo 1 - vertical slice and coroutine concurrency (branch demo-1)
# ---------------------------------------------------------------------------
demo1() {
	log "== Demo 1 - vertical slice + coroutine concurrency (demo-1)"
	need_branch demo-1 || { record "demo-1" "FAIL" "could not check out demo-1"; return; }
	ensure_downstreams || { record "demo-1" "FAIL" "downstream stubs did not start"; return; }
	ensure_app "default" "$RUN_DIR/app-demo1.log" || { record "demo-1" "FAIL" "app did not start"; return; }

	# warm-up bet (class loading / connection setup pollutes the first timing)
	place_bet_insecure "G-100" "HOME" 10 >/dev/null

	local timed elapsed
	timed="$(curl -s -o /dev/null -w '%{time_total}' -X POST "$BASE_URL/api/v1/bets" \
		-H 'X-Customer-Id: C-100' -H 'Content-Type: application/json' \
		-d '{"gameId":"G-100","selection":"AWAY","stake":250}')"

	local timing_ok order_ok thread_ok
	# doc: ~0.88 s == max(800,600)ms + overhead; 1.4 s would mean serial execution
	timing_ok="$(awk -v t="$timed" 'BEGIN { print (t >= 0.5 && t <= 1.2) ? "yes" : "no" }')"

	# decisive evidence: for the latest bet, both "started" lines precede both
	# "completed" lines in the downstream log
	order_ok="$(grep -E '^(RISK|ODDS) +(started|completed)' "$DOWNSTREAM_LOG" | tail -4 \
		| awk 'NR<=2 && /started/ {s++} NR>=3 && /completed/ {c++} END { print (s==2 && c==2) ? "yes" : "no" }')"

	# the launch lines share one HTTP thread; "bet accepted" resumes on ForkJoinPool
	thread_ok="no"
	if grep -q 'both validations launched' "$APP_LOG" \
		&& grep 'bet accepted' "$APP_LOG" | grep -q 'ForkJoinPool'; then
		thread_ok="yes"
	fi

	local detail="time=${timed}s (doc ~0.88s, serial would be 1.4s); started-before-completed=$order_ok; forkjoin-bet-accepted=$thread_ok"
	if [[ $timing_ok == "yes" && $order_ok == "yes" && $thread_ok == "yes" ]]; then
		record "demo-1 concurrency" "PASS" "$detail"
	else
		record "demo-1 concurrency" "FAIL" "$detail (logs: $DOWNSTREAM_LOG, $APP_LOG)"
	fi
}

# ---------------------------------------------------------------------------
# Demo 2 - Spring Security and the error contract (branch demo-2)
# ---------------------------------------------------------------------------
demo2() {
	log "== Demo 2 - Spring Security + error contract (demo-2)"
	need_branch demo-2 || { record "demo-2" "FAIL" "could not check out demo-2"; return; }
	ensure_downstreams || { record "demo-2" "FAIL" "downstream stubs did not start"; return; }
	ensure_app "default" "$RUN_DIR/app-demo2.log" || { record "demo-2" "FAIL" "app did not start"; return; }
	load_tokens || { record "demo-2" "FAIL" "token generation failed"; return; }

	# 1: no token -> 401 AUTHENTICATION_REQUIRED, 3-field body
	split_code_body "$(curl -s -o - -w $'\t%{http_code}' -X POST "$BASE_URL/api/v1/bets" \
		-H 'Content-Type: application/json' \
		-d '{"gameId":"G-100","selection":"HOME","stake":100}')"
	if [[ $CODE == "401" && $(printf '%s' "$BODY" | json_eval '(d.get("code"), len(d))') == "('AUTHENTICATION_REQUIRED', 3)" ]]; then
		record "demo-2 (1) no token" "PASS" "401 AUTHENTICATION_REQUIRED, 3-field body"
	else
		record "demo-2 (1) no token" "FAIL" "got $CODE $BODY"
	fi

	# 2: valid token, no scope -> 403 INSUFFICIENT_SCOPE, 3-field body
	split_code_body "$(curl -s -o - -w $'\t%{http_code}' -X POST "$BASE_URL/api/v1/bets" \
		-H "Authorization: Bearer $VALID_NO_SCOPE_TOKEN" -H 'Content-Type: application/json' \
		-d '{"gameId":"G-100","selection":"HOME","stake":100}')"
	if [[ $CODE == "403" && $(printf '%s' "$BODY" | json_eval '(d.get("code"), len(d))') == "('INSUFFICIENT_SCOPE', 3)" ]]; then
		record "demo-2 (2) no scope" "PASS" "403 INSUFFICIENT_SCOPE, 3-field body"
	else
		record "demo-2 (2) no scope" "FAIL" "got $CODE $BODY"
	fi

	# 3: unknown game -> 404 GAME_NOT_FOUND, full six-field contract, echoed traceId
	split_code_body "$(curl -s -o - -w $'\t%{http_code}' -X POST "$BASE_URL/api/v1/bets" \
		-H "Authorization: Bearer $VALID_BETS_WRITE_TOKEN" -H 'Content-Type: application/json' \
		-H 'X-Trace-Id: demo-404' \
		-d '{"gameId":"G-404","selection":"HOME","stake":100}')"
	if [[ $CODE == "404" && $(printf '%s' "$BODY" | json_eval \
		'(d.get("code"), d.get("traceId"), sorted(d.keys()))') \
		== "('GAME_NOT_FOUND', 'demo-404', ['code', 'message', 'path', 'status', 'timestamp', 'traceId'])" ]]; then
		record "demo-2 (3) unknown game" "PASS" "404 GAME_NOT_FOUND, six-field contract, traceId echoed"
	else
		record "demo-2 (3) unknown game" "FAIL" "got $CODE $BODY"
	fi

	# 4: everything correct -> 201 ACCEPTED with odds 1.85
	split_code_body "$(place_bet_secured "G-100" "HOME" 100)"
	if [[ $CODE == "201" && $(printf '%s' "$BODY" | json_eval '(d.get("odds"), d.get("status"), bool(d.get("betId")))') \
		== "(1.85, 'ACCEPTED', True)" ]]; then
		record "demo-2 (4) valid bet" "PASS" "201, odds=1.85, status=ACCEPTED"
	else
		record "demo-2 (4) valid bet" "FAIL" "got $CODE $BODY"
	fi

	# 5: read game with games:read -> 200 with both odds
	split_code_body "$(curl -s -o - -w $'\t%{http_code}' "$BASE_URL/api/v1/games/G-100" \
		-H "Authorization: Bearer $VALID_GAMES_READ_TOKEN")"
	if [[ $CODE == "200" && $(printf '%s' "$BODY" | json_eval \
		'(bool(d.get("homeTeam")), bool(d.get("homeOdds")), bool(d.get("awayOdds")))') \
		== "(True, True, True)" ]]; then
		record "demo-2 (5) read game" "PASS" "200, team names + both odds"
	else
		record "demo-2 (5) read game" "FAIL" "got $CODE $BODY"
	fi

	return 0
}

# ---------------------------------------------------------------------------
# Demo 3 - durable event and consumer group (branch demo-3, needs solace)
# ---------------------------------------------------------------------------
semp_queue_count() { # queue-name-substring -> spooledMsgCount or empty
	curl -s -u "$SEMP_AUTH" "$SEMP_URL" 2>/dev/null | json_eval \
		'next((str(q.get("spooledMsgCount")) for q in d.get("data", []) if "'"$1"'" in q.get("queueName", "")), "")'
}

demo3() {
	log "== Demo 3 - durable event + consumer group (demo-3)"
	need_branch demo-3 || { record "demo-3" "FAIL" "could not check out demo-3"; return; }
	e2_memory_check
	if ! ensure_container solace betting-solace "$CONTAINER_TIMEOUT"; then
		record "demo-3" "FAIL" "betting-solace did not become healthy (see E2 warning above)"
		return
	fi
	ensure_downstreams || { record "demo-3" "FAIL" "downstream stubs did not start"; return; }
	load_tokens || { record "demo-3" "FAIL" "token generation failed"; return; }
	ensure_app "solace" "$RUN_DIR/app-demo3.log" "--spring.profiles.active=solace" \
		|| { record "demo-3" "FAIL" "app did not start with the solace profile"; return; }

	split_code_body "$(place_bet_secured "G-100" "HOME" 100)"
	if [[ $CODE != "201" ]]; then
		record "demo-3 messaging" "FAIL" "bet returned $CODE, expected an immediate 201: $BODY"
		return
	fi

	# the consumer line arrives asynchronously - give the broker a minute
	local pub_ok="no" cons_ok="no"
	wait_log "$APP_LOG" 'SolaceBetPlacedPublisher.*event published.*destination=betPlaced-out-0' 30 \
		&& pub_ok="yes"
	if wait_log "$APP_LOG" 'solace-scst-consumer-[^ ]*.*NotificationBetPlacedConsumer.*event notified' "$CONSUMER_TIMEOUT"; then
		cons_ok="yes"
	fi

	local count
	count="$(semp_queue_count "notify-bet-placed")"
	local queue_ok="no"
	[[ -n $count && $count -ge 1 ]] 2>/dev/null && queue_ok="yes"

	local detail="201-immediate=yes; published-line=$pub_ok; consumer-thread-line=$cons_ok; queue spooledMsgCount=${count:-unavailable}"
	if [[ $pub_ok == "yes" && $cons_ok == "yes" && $queue_ok == "yes" ]]; then
		record "demo-3 messaging" "PASS" "$detail"
	else
		record "demo-3 messaging" "FAIL" "$detail (log: $APP_LOG)"
	fi
}

# ---------------------------------------------------------------------------
# Demo 4.1 - TDD three-step uncomment cycle (branch demo-4)
# ---------------------------------------------------------------------------
TDD_TEST="src/test/kotlin/com/hkjc/training/betting/BetControllerTest.kt"
TDD_SERVICE="src/main/kotlin/com/hkjc/training/betting/service/BetService.kt"
TDD_HANDLER="src/main/kotlin/com/hkjc/training/betting/exception/ApiExceptionHandler.kt"

# uncomment the code lines inside one "=== Demo TDD" block; descriptive comment
# lines stay commented (only @/fun/if/throw/}/)/deeper-indented lines are code)
tdd_uncomment() { # file, marker substring
	python3 - "$1" "$2" <<'PY'
import re, sys
path, marker = sys.argv[1], sys.argv[2]
lines = open(path).read().splitlines(keepends=True)
out, inside = [], False
for ln in lines:
    if marker in ln:
        inside = True
    elif inside and "=== end ===" in ln:
        inside = False
    elif inside:
        m = re.match(r"^(\s*)// (.*)$", ln.rstrip("\n"))
        if m and re.match(r"^(@|fun |if |throw |}|\)|\s)", m.group(2)):
            ln = m.group(1) + m.group(2) + "\n"
    out.append(ln)
open(path, "w").writelines(out)
PY
}

tdd_markers_commented() { # 0 = exactly 3 markers and all are comments
	local n
	n="$(grep -rn "=== Demo TDD" src/ --include='*.kt' | wc -l | tr -d ' ')"
	[[ $n == "3" ]] || return 1
	! grep -rn "=== Demo TDD" src/ --include='*.kt' | grep -vq '// === Demo TDD'
}

# grep a red-state assertion message in the fresh test XML (angle brackets may
# be XML-escaped) or in the gradle console output
red_message_found() { # 201-or-500
	local want=$1
	grep -rhE "expected:(&lt;|<)400(&gt;|>) but was:(&lt;|<)$want(&gt;|>)" \
		build/test-results/test/ "$RUN_DIR/tdd-run.log" 2>/dev/null | grep -q .
}

demo4_1() {
	log "== Demo 4.1 - TDD cycle (demo-4)"
	need_branch demo-4 || { record "demo-4.1" "FAIL" "could not check out demo-4"; return; }
	stop_app; stop_downstreams

	if ! tdd_markers_commented; then
		record "demo-4.1 TDD" "FAIL" "pre-state broken: expected 3 commented '=== Demo TDD' markers"
		return
	fi

	# step 1: uncomment the test only -> red 400-vs-201
	rm -rf build/test-results/test   # no stale XML may fake a red state
	tdd_uncomment "$TDD_TEST" "step 1"
	./gradlew test --tests '*BetControllerTest' --rerun-tasks >"$RUN_DIR/tdd-run.log" 2>&1
	local s1="no"; red_message_found 201 && s1="yes"
	git checkout -- "$TDD_TEST" >/dev/null 2>&1
	if [[ $s1 != "yes" ]]; then
		record "demo-4.1 TDD" "FAIL" "step 1 did not go red with expected:<400> but was:<201> (log: $RUN_DIR/tdd-run.log)"
		git checkout -- src/ >/dev/null 2>&1
		return
	fi
	record "demo-4.1 step 1 (red 201)" "PASS" "test alone -> Status expected:<400> but was:<201>"

	# step 2: uncomment the stake rule -> red moves to 400-vs-500
	rm -rf build/test-results/test
	tdd_uncomment "$TDD_TEST" "step 1"
	tdd_uncomment "$TDD_SERVICE" "step 2"
	./gradlew test --tests '*BetControllerTest' --rerun-tasks >"$RUN_DIR/tdd-run.log" 2>&1
	local s2="no"; red_message_found 500 && s2="yes"
	if [[ $s2 != "yes" ]]; then
		git checkout -- src/ >/dev/null 2>&1
		record "demo-4.1 TDD" "FAIL" "step 2 did not go red with expected:<400> but was:<500> (log: $RUN_DIR/tdd-run.log)"
		return
	fi
	record "demo-4.1 step 2 (red 500)" "PASS" "rule live, exception unmapped -> expected:<400> but was:<500>"

	# step 3: uncomment the exception mapping -> all green
	tdd_uncomment "$TDD_HANDLER" "step 3"
	./gradlew test --rerun-tasks >"$RUN_DIR/tdd-run.log" 2>&1
	local s3="no"
	grep -q 'BUILD SUCCESSFUL' "$RUN_DIR/tdd-run.log" && s3="yes"
	git checkout -- src/ >/dev/null 2>&1
	if [[ $s3 == "yes" ]] && tdd_markers_commented; then
		record "demo-4.1 step 3 (green)" "PASS" "full ./gradlew test green; worktree restored, markers commented"
	else
		record "demo-4.1 step 3 (green)" "FAIL" "full test run not green or restore failed (log: $RUN_DIR/tdd-run.log)"
	fi
}

# ---------------------------------------------------------------------------
# Demo 4.2 / 4.3 / 4.4
# ---------------------------------------------------------------------------
demo4_2() {
	log "== Demo 4.2 - BetServiceTest (demo-4)"
	need_branch demo-4 || { record "demo-4.2" "FAIL" "could not check out demo-4"; return; }
	stop_app; stop_downstreams
	./gradlew test --tests '*BetServiceTest' --rerun-tasks >"$RUN_DIR/demo42.log" 2>&1
	if grep -q 'BUILD SUCCESSFUL' "$RUN_DIR/demo42.log"; then
		record "demo-4.2 BetServiceTest" "PASS" "unit tests green, no DB/broker/network"
	else
		record "demo-4.2 BetServiceTest" "FAIL" "see $RUN_DIR/demo42.log"
	fi
}

demo4_3() {
	log "== Demo 4.3 - contractTest (demo-4)"
	need_branch demo-4 || { record "demo-4.3" "FAIL" "could not check out demo-4"; return; }
	stop_app; stop_downstreams
	rm -rf build/pacts   # generated output; the doc's point is that it appears only now
	./gradlew contractTest >"$RUN_DIR/demo43.log" 2>&1
	local pacts
	pacts="$(find build/pacts -name '*.json' 2>/dev/null | wc -l | tr -d ' ')"
	if grep -q 'BUILD SUCCESSFUL' "$RUN_DIR/demo43.log" && [[ $pacts -ge 1 ]]; then
		record "demo-4.3 contractTest" "PASS" "green; build/pacts/ appeared with $pacts pact file(s)"
	else
		record "demo-4.3 contractTest" "FAIL" "build failed or no pacts produced (log: $RUN_DIR/demo43.log)"
	fi
}

demo4_4() {
	log "== Demo 4.4 - PactFlow (demo-4)"
	if (( PACTFLOW == 0 )); then
		record "demo-4.4 PactFlow" "SKIP" "not automated by default; re-run with --pactflow to execute against a tenant"
		return
	fi
	if [[ -z ${PACT_BROKER_BASE_URL:-} || -z ${PACT_BROKER_TOKEN:-} ]]; then
		record "demo-4.4 PactFlow" "SKIP" "PACT_BROKER_BASE_URL / PACT_BROKER_TOKEN not set; per the doc this step becomes screenshot narration"
		return
	fi
	need_branch demo-4 || { record "demo-4.4" "FAIL" "could not check out demo-4"; return; }
	export GIT_COMMIT GIT_BRANCH
	GIT_COMMIT="$(git rev-parse HEAD)"
	GIT_BRANCH="$(git branch --show-current)"
	./gradlew contractTest pactPublish >"$RUN_DIR/demo44.log" 2>&1 \
		&& ./gradlew canIDeploy >>"$RUN_DIR/demo44.log" 2>&1
	local no_first="no"
	grep -q 'Computer says no' "$RUN_DIR/demo44.log" && no_first="yes"
	./gradlew pactBrokerVerify >>"$RUN_DIR/demo44.log" 2>&1 \
		&& ./gradlew canIDeploy >>"$RUN_DIR/demo44.log" 2>&1
	local yes_second="no"
	tail -40 "$RUN_DIR/demo44.log" | grep -q 'Computer says yes' && yes_second="yes"
	if [[ $no_first == "yes" && $yes_second == "yes" ]]; then
		record "demo-4.4 PactFlow" "PASS" "publish -> canIDeploy no -> verify -> canIDeploy yes"
	else
		record "demo-4.4 PactFlow" "FAIL" "expected no-then-yes sequence not seen (log: $RUN_DIR/demo44.log)"
	fi
}

# ---------------------------------------------------------------------------
# Demo 5 - Gradle structure (any branch)
# ---------------------------------------------------------------------------
demo5() {
	log "== Demo 5 - Gradle build structure"
	need_branch demo-4 || { record "demo-5" "FAIL" "could not check out demo-4"; return; }
	stop_app; stop_downstreams
	./gradlew tasks --group=verification >"$RUN_DIR/demo5-tasks.log" 2>&1
	local t1=$?
	./gradlew dependencies --configuration runtimeClasspath >"$RUN_DIR/demo5-deps.log" 2>&1
	local t2=$?
	if (( t1 == 0 && t2 == 0 )) \
		&& grep -q 'contractTest' "$RUN_DIR/demo5-tasks.log" \
		&& grep -q 'integrationTest' "$RUN_DIR/demo5-tasks.log" \
		&& grep -q -- '->' "$RUN_DIR/demo5-deps.log"; then
		record "demo-5 gradle" "PASS" "verification tasks listed; runtimeClasspath shows BOM-mediated resolution"
	else
		record "demo-5 gradle" "FAIL" "tasks rc=$t1 deps rc=$t2 (logs in $RUN_DIR)"
	fi
}

# ---------------------------------------------------------------------------
# Demo 6 - four packaging shapes (any branch)
# ---------------------------------------------------------------------------
demo6() {
	log "== Demo 6 - packaging shapes"
	need_branch demo-4 || { record "demo-6" "FAIL" "could not check out demo-4"; return; }
	stop_app; stop_downstreams
	if ! ./gradlew bootJar jar thinJar slimDist >"$RUN_DIR/demo6.log" 2>&1; then
		record "demo-6 packaging" "FAIL" "gradle packaging failed (log: $RUN_DIR/demo6.log)"
		return
	fi
	local boot plain thin libs fails=0 detail=""
	boot="$(stat -f %z build/libs/basketball-betting-0.0.1-SNAPSHOT.jar 2>/dev/null || echo 0)"
	plain="$(stat -f %z build/libs/basketball-betting-0.0.1-SNAPSHOT-plain.jar 2>/dev/null || echo 0)"
	thin="$(stat -f %z build/libs/basketball-betting.jar 2>/dev/null || echo 0)"
	libs="$(find build/slim/basketball-betting/lib -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')"
	(( boot > 40000000 )) || { fails=$((fails + 1)); detail="$detail boot-jar-size=${boot}B(expected ~59M);"; }
	(( plain > 50000 && plain < 1000000 )) || { fails=$((fails + 1)); detail="$detail plain-jar-size=${plain}B(expected ~103K);"; }
	(( thin > 50000 && thin < 1000000 )) || { fails=$((fails + 1)); detail="$detail thin-jar-size=${thin}B(expected ~103K);"; }
	[[ $libs == "149" ]] || { fails=$((fails + 1)); detail="$detail slim-lib-count=$libs(expected 149);"; }

	# demo classes must not leak into the production artifact
	local democ
	democ="$(unzip -l build/libs/basketball-betting.jar 2>/dev/null | grep -c 'betting/demo/')" || true
	[[ $democ == "0" ]] || { fails=$((fails + 1)); detail="$detail demo-classes-in-artifact=$democ(expected 0);"; }

	# the slim distribution must actually boot and serve /actuator/health
	local booted="no" health=""
	if [[ -x build/slim/basketball-betting/bin/basketball-betting ]]; then
		SLIM_LOG="$RUN_DIR/demo6-slim.log"; : >"$SLIM_LOG"
		if port_open 8080; then
			detail="$detail slim-boot=skipped-port-8080-busy;"
		else
			(cd build/slim/basketball-betting && ./bin/basketball-betting) >"$SLIM_LOG" 2>&1 &
			SLIM_PID=$!
			if wait_started "$SLIM_LOG" "$SLIM_PID" "$BOOT_TIMEOUT"; then
				booted="yes"
				health="$(curl -s "$BASE_URL/actuator/health" | json_eval 'd.get("status","")')"
			fi
			stop_slim
		fi
		[[ $booted == "yes" && $health == "UP" ]] \
			|| { fails=$((fails + 1)); detail="$detail slim-boot=$booted health=${health:-none};"; }
	else
		fails=$((fails + 1)); detail="$detail slim-script-missing;"
	fi

	if (( fails == 0 )); then
		record "demo-6 packaging" "PASS" "boot=${boot}B plain=${plain}B thin=${thin}B slim-libs=$libs; slim boots, health UP; demo classes absent"
	else
		record "demo-6 packaging" "FAIL" "$detail"
	fi
}

# ---------------------------------------------------------------------------
# Demo 7 - Flyway migrations (any branch, needs postgres)
# ---------------------------------------------------------------------------
psql_in_container() { # SQL
	docker exec betting-postgres psql -U betting -d betting -t -A -c "$1" 2>&1
}

demo7() {
	log "== Demo 7 - Flyway migrations"
	need_branch demo-4 || { record "demo-7" "FAIL" "could not check out demo-4"; return; }
	stop_downstreams
	if ! ensure_container postgres betting-postgres 120; then
		record "demo-7" "FAIL" "betting-postgres did not become healthy"
		return
	fi

	# baseline: on a fresh database flyway_schema_history does not exist yet
	# (doc gap D1) - bootstrap it with one database-profile boot
	local history
	history="$(psql_in_container "select string_agg(version || ':' || success::text, ',' order by installed_rank) from flyway_schema_history")"
	if [[ $history == *"does not exist"* ]]; then
		info "fresh database (no flyway_schema_history yet) - bootstrapping once with the database profile"
		ensure_app "database" "$RUN_DIR/app-demo7-boot.log" "--spring.profiles.active=database" "${DB_ARGS[@]+"${DB_ARGS[@]}"}" \
			|| { record "demo-7" "FAIL" "bootstrap boot failed; if this is E1, see the warning above"; return; }
		stop_app
		history="$(psql_in_container "select string_agg(version || ':' || success::text, ',' order by installed_rank) from flyway_schema_history")"
	fi
	if [[ $history != "1:true,2:true,3:true" ]]; then
		record "demo-7 baseline" "FAIL" "unexpected flyway history: $history"
		return
	fi
	record "demo-7 baseline" "PASS" "flyway_schema_history = V1,V2,V3 all successful"

	# step 2: enable V4 -> migrate 3 -> 4
	mv "$v4_disabled" "$v4_sql"
	local applog="$RUN_DIR/app-demo7-v4.log"
	if ! ensure_app "database" "$applog" "--spring.profiles.active=database" "${DB_ARGS[@]+"${DB_ARGS[@]}"}"; then
		restore_v4
		git checkout -- src/ >/dev/null 2>&1
		record "demo-7 V4 migrate" "FAIL" "app did not start with V4 enabled (log: $applog)"
		return
	fi
	FLYWAY_V4_APPLIED=1
	stop_app
	local lines_ok="yes"
	for pat in 'Successfully validated 4 migrations' 'Current version of schema "public": 3' \
		'Migrating schema "public" to version "4 - index bet by game"' \
		'Successfully applied 1 migration to schema "public", now at version v4'; do
		grep -qF "$pat" "$applog" || lines_ok="no"
	done
	if [[ $lines_ok == "yes" ]]; then
		record "demo-7 V4 migrate" "PASS" "all four documented migration log lines appeared"
	else
		record "demo-7 V4 migrate" "FAIL" "missing migration log lines (log: $applog)"
	fi

	# step 3: tamper with the already-applied V2 -> startup must fail on checksum
	printf 'create index idx_bet_tamper on bet (stake);\n' \
		>> src/main/resources/db/migration/V2__create_bet_table.sql
	local tlog="$RUN_DIR/app-demo7-tamper.log"
	stop_app
	: >"$tlog"
	./gradlew bootRun --args="--spring.profiles.active=database $DB_URL_ARG" >"$tlog" 2>&1 &
	local tamper_pid=$!
	local checksum_ok="no"
	if wait_log "$tlog" 'Migration checksum mismatch for migration version 2' 240; then
		checksum_ok="yes"
	fi
	kill "$tamper_pid" >/dev/null 2>&1; pkill -f "$APP_CLASS" >/dev/null 2>&1; wait "$tamper_pid" >/dev/null 2>&1
	APP_PID=""; APP_KEY=""
	git checkout -- src/ >/dev/null 2>&1
	if [[ $checksum_ok == "yes" ]]; then
		record "demo-7 checksum" "PASS" "tampered V2 -> startup fails: checksum mismatch for migration version 2"
	else
		record "demo-7 checksum" "FAIL" "no checksum-mismatch failure within timeout (log: $tlog)"
	fi

	# step 4: full restore - V4 back to .disabled, history row + demo index dropped
	restore_v4
	git checkout -- src/ >/dev/null 2>&1
	psql_in_container "delete from flyway_schema_history where version='4'" >/dev/null
	psql_in_container "drop index if exists idx_bet_game_id" >/dev/null
	FLYWAY_V4_APPLIED=0
	local final
	final="$(psql_in_container "select string_agg(version, ',' order by installed_rank) from flyway_schema_history")"
	if [[ $final == "1,2,3" && -f $v4_disabled && ! -f $v4_sql ]]; then
		record "demo-7 restore" "PASS" "history back to V1-V3; V4 is .sql.disabled again"
	else
		record "demo-7 restore" "FAIL" "final history: $final"
	fi
}

# ---------------------------------------------------------------------------
# Demo 8 - Spring profiles select adapters (any branch)
# ---------------------------------------------------------------------------
demo8() {
	log "== Demo 8 - Spring profiles"
	need_branch demo-4 || { record "demo-8" "FAIL" "could not check out demo-4"; return; }

	# the doc's @Profile table: 11 annotations, six profile expressions
	local n_profiles missing=""
	n_profiles="$(grep -rn '@Profile' src/main/kotlin | wc -l | tr -d ' ')"
	for p in '"database"' '"!database"' '"solace"' '"!solace"' '"test"' '"!test"'; do
		grep -rq "@Profile($p)" src/main/kotlin || missing="$missing $p"
	done
	if [[ $n_profiles == "11" && -z $missing ]]; then
		record "demo-8 @Profile table" "PASS" "11 annotations; database/solace/test pairs all present"
	else
		record "demo-8 @Profile table" "FAIL" "count=$n_profiles (expected 11); missing:$missing"
	fi

	load_tokens || { record "demo-8 boots" "FAIL" "token generation failed"; return; }
	ensure_downstreams || { record "demo-8 boots" "FAIL" "downstream stubs did not start"; return; }
	ensure_container postgres betting-postgres 120 || { record "demo-8 boots" "FAIL" "postgres unavailable"; return; }
	e2_memory_check
	ensure_container solace betting-solace "$CONTAINER_TIMEOUT" || { record "demo-8 boots" "FAIL" "solace unavailable (see E2 warning)"; return; }

	# combo 1: no profile -> all in-memory
	if ensure_app "default" "$RUN_DIR/app-demo8-default.log"; then
		split_code_body "$(place_bet_secured "G-100" "HOME" 100)"
		if [[ $CODE == "201" ]] && grep -q 'No active profile set' "$APP_LOG" \
			&& grep -q 'InMemoryBetPlacedPublisher.*destination=in-memory' "$APP_LOG"; then
			record "demo-8 boot default" "PASS" "no profile; 201; InMemoryBetPlacedPublisher destination=in-memory"
		else
			record "demo-8 boot default" "FAIL" "code=$CODE (log: $APP_LOG)"
		fi
	else
		record "demo-8 boot default" "FAIL" "app did not start (log: $RUN_DIR/app-demo8-default.log)"
	fi

	# combo 2: database profile -> JDBC adapters
	if ensure_app "database" "$RUN_DIR/app-demo8-db.log" "--spring.profiles.active=database" "${DB_ARGS[@]+"${DB_ARGS[@]}"}"; then
		split_code_body "$(place_bet_secured "G-100" "HOME" 100)"
		local rows
		rows="$(psql_in_container "select count(*) from bet")"
		if [[ $CODE == "201" ]] && grep -q '1 profile is active: database' "$APP_LOG" \
			&& [[ $rows =~ ^[0-9]+$ ]] && (( rows >= 1 )); then
			record "demo-8 boot database" "PASS" "201; '1 profile is active: database'; bet row in Postgres (count=$rows)"
		else
			record "demo-8 boot database" "FAIL" "code=$CODE rows=$rows (log: $APP_LOG)"
		fi
	else
		record "demo-8 boot database" "FAIL" "app did not start; if this is E1, see the warning above"
	fi

	# combo 3: database + solace -> both real adapters
	if ensure_app "database,solace" "$RUN_DIR/app-demo8-dbsolace.log" \
		"--spring.profiles.active=database,solace" "${DB_ARGS[@]+"${DB_ARGS[@]}"}"; then
		split_code_body "$(place_bet_secured "G-100" "HOME" 100)"
		local notified="no"
		wait_log "$APP_LOG" 'solace-scst-consumer-[^ ]*.*event notified' "$CONSUMER_TIMEOUT" && notified="yes"
		if [[ $CODE == "201" ]] && grep -q '2 profiles are active: database, solace' "$APP_LOG" \
			&& [[ $notified == "yes" ]]; then
			record "demo-8 boot database,solace" "PASS" "201; both profiles active; event consumed from the broker queue"
		else
			record "demo-8 boot database,solace" "FAIL" "code=$CODE notified=$notified (log: $APP_LOG)"
		fi
	else
		record "demo-8 boot database,solace" "FAIL" "app did not start (log: $RUN_DIR/app-demo8-dbsolace.log)"
	fi
}

# ---------------------------------------------------------------------------
# Demo 9 - one trace id across API, database and broker (demo-3 or demo-4)
# ---------------------------------------------------------------------------
demo9() {
	log "== Demo 9 - trace + metrics (demo-4, database+solace)"
	need_branch demo-4 || { record "demo-9" "FAIL" "could not check out demo-4"; return; }
	load_tokens || { record "demo-9" "FAIL" "token generation failed"; return; }
	ensure_downstreams || { record "demo-9" "FAIL" "downstream stubs did not start"; return; }
	ensure_container postgres betting-postgres 120 || { record "demo-9" "FAIL" "postgres unavailable"; return; }
	e2_memory_check
	ensure_container solace betting-solace "$CONTAINER_TIMEOUT" || { record "demo-9" "FAIL" "solace unavailable (see E2 warning)"; return; }

	if ! ensure_app "database,solace" "$RUN_DIR/app-demo9.log" \
		"--spring.profiles.active=database,solace" "${DB_ARGS[@]+"${DB_ARGS[@]}"}"; then
		record "demo-9 tracing" "FAIL" "app did not start with database,solace (log: $RUN_DIR/app-demo9.log)"
		return
	fi

	local hdrs="$RUN_DIR/demo9-headers.txt"
	split_code_body "$(place_bet_secured "G-100" "HOME" 100 "demo9-check" "$hdrs")"
	if [[ $CODE != "201" ]]; then
		record "demo-9 tracing" "FAIL" "bet returned $CODE: $BODY"
		return
	fi
	# the consumer line is asynchronous - wait before grepping the four phases
	wait_log "$APP_LOG" 'demo9-check.*NotificationBetPlacedConsumer.*event notified' "$CONSUMER_TIMEOUT" >/dev/null 2>&1

	local echoed="no" phases=0
	grep -qi '^X-Trace-Id: demo9-check' "$hdrs" 2>/dev/null && echoed="yes"
	grep 'demo9-check' "$APP_LOG" | grep -q 'TraceIdFilter.*request received' && phases=$((phases + 1))
	grep 'demo9-check' "$APP_LOG" | grep -q 'BetService.*bet accepted' && phases=$((phases + 1))
	grep 'demo9-check' "$APP_LOG" | grep -q 'SolaceBetPlacedPublisher.*event published' && phases=$((phases + 1))
	grep 'demo9-check' "$APP_LOG" | grep -q 'NotificationBetPlacedConsumer.*event notified' && phases=$((phases + 1))

	if [[ $echoed == "yes" && $phases == 4 ]]; then
		record "demo-9 tracing" "PASS" "X-Trace-Id echoed; 4 phases share demo9-check across 3 threads + broker"
	else
		record "demo-9 tracing" "FAIL" "echoed=$echoed phases=$phases/4 (log: $APP_LOG)"
	fi

	# metrics: index, lazy http.server.requests with measurements, prometheus scrape
	local m_index m_hsr m_prom
	m_index="$(curl -s -o - -w '%{http_code}' "$BASE_URL/actuator/metrics")"
	m_hsr="$(curl -s "$BASE_URL/actuator/metrics/http.server.requests")"
	m_prom="$(curl -s "$BASE_URL/actuator/prometheus")"
	if [[ $m_index == *200 && $m_index == *"http.server.requests"* ]] \
		&& [[ $(printf '%s' "$m_hsr" | json_eval '"measurements" in d') == "True" ]] \
		&& [[ $m_prom == *"# HELP"* ]]; then
		record "demo-9 metrics" "PASS" "metrics index + measurements + prometheus scrape all respond"
	else
		record "demo-9 metrics" "FAIL" "index/measurements/prometheus not all healthy"
	fi
}

# final guard: the worktree must be back to the doc's clean-state checklist
final_clean_check() {
	restore_repo_state
	local problems=""
	[[ -n $(git status --porcelain) ]] && problems="$problems git-status-not-empty;"
	if git rev-parse --verify -q demo-4 >/dev/null 2>&1; then
		git show demo-4:"$v4_disabled" >/dev/null 2>&1 || problems="$problems V4-not-disabled-on-demo-4;"
		[[ $(git grep -c '=== Demo TDD' demo-4 -- '*.kt' | wc -l | tr -d ' ') == "3" ]] \
			|| problems="$problems TDD-markers-not-3-on-demo-4;"
	fi
	if [[ -z $problems ]]; then
		record "clean-state checklist" "PASS" "git status empty; V4 .sql.disabled; 3 TDD markers commented"
	else
		record "clean-state checklist" "FAIL" "$problems"
	fi
}

# ---------------------------------------------------------------------------
# argument parsing and main
# ---------------------------------------------------------------------------
usage() {
	sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
}

DEMOS=()
while (($#)); do
	case "$1" in
		--demo) DEMOS+=("$2"); shift 2 ;;
		--demo=*) DEMOS+=("${1#--demo=}"); shift ;;
		--pactflow) PACTFLOW=1; shift ;;
		-h | --help) usage; exit 0 ;;
		*) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
	esac
done
if [[ ${#DEMOS[@]} -eq 0 ]]; then
	DEMOS=(1 2 3 4.1 4.2 4.3 4.4 5 6 7 8 9)
else
	# expand "4" into its four sub-scenarios
	EXPANDED=()
	for d in "${DEMOS[@]}"; do
		if [[ $d == "4" ]]; then EXPANDED+=(4.1 4.2 4.3 4.4); else EXPANDED+=("$d"); fi
	done
	DEMOS=("${EXPANDED[@]}")
fi

want() { # demo number
	local d
	for d in "${DEMOS[@]}"; do [[ $d == "$1" ]] && return 0; done
	return 1
}

log "auto-demo: running scenarios: ${DEMOS[*]}"
log "auto-demo: repo=$REPO original-ref=$ORIG_REF logs=$RUN_DIR"

# environment preflight
setup_docker_env || warn "docker compose not found; demos needing postgres/solace will FAIL"
if want 7 || want 8 || want 9; then
	detect_pg_shadow && apply_pg_workaround
fi

want 1   && demo1
want 2   && demo2
want 3   && demo3
want 4.1 && demo4_1
want 4.2 && demo4_2
want 4.3 && demo4_3
want 4.4 && demo4_4
want 5   && demo5
want 6   && demo6
want 7   && demo7
want 8   && demo8
want 9   && demo9

final_clean_check
print_table
