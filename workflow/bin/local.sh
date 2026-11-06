#!/usr/bin/env bash
# Runs the app locally the way it runs deployed: JDK 11 (the image's JDK; newer JDKs format dates
# differently), Postgres from docker-compose.yml, port 8080. Pid and log in .local/.
#
# Usage: workflow/bin/local.sh up       build (no tests), (re)start, wait for /health
#        workflow/bin/local.sh restart  same as up
#        workflow/bin/local.sh down     stop the app (Postgres keeps running; `docker compose down` stops it)
#        workflow/bin/local.sh status   health of the running app
#        workflow/bin/local.sh logs     tail the app log
#        workflow/bin/local.sh image    deploy rehearsal: build the root Dockerfile like deploy.yml does
#                                       (linux/amd64, GIT_SHA baked in), run it on :8081 against the Compose
#                                       Postgres, and require /health to report that SHA, like rollout.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
mkdir -p .local
pidfile=.local/app.pid
log=.local/app.log
url=http://localhost:8080

export JAVA_HOME="${JAVA_HOME_11:-$(/usr/libexec/java_home -v 11)}"

stop() {
    if [[ -f $pidfile ]] && kill -0 "$(cat $pidfile)" 2>/dev/null; then
        kill "$(cat $pidfile)"
        while kill -0 "$(cat $pidfile)" 2>/dev/null; do sleep 0.2; done
        echo "stopped"
    fi
    rm -f $pidfile
}

db_up() {
    docker compose up -d postgres >/dev/null
    for _ in $(seq 1 30); do
        docker compose exec -T postgres pg_isready -U cfp -q && return
        sleep 1
    done
    echo "postgres didn't become ready" >&2
    exit 1
}

start() {
    if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
        echo "port 8080 is taken by something else" >&2
        exit 1
    fi
    sha="local-$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- src pom.xml || echo -dirty)"
    GIT_SHA="$sha" nohup "$JAVA_HOME/bin/java" -jar target/cfp.jar >"$log" 2>&1 &
    echo $! >$pidfile
    for _ in $(seq 1 60); do
        if curl -fsS "$url/health" 2>/dev/null; then
            echo
            return
        fi
        kill -0 "$(cat $pidfile)" 2>/dev/null || { tail -40 "$log"; echo "app died, see $log" >&2; exit 1; }
        sleep 1
    done
    echo "no healthy /health within 60 s, see $log" >&2
    exit 1
}

image() {
    sha="$(git rev-parse HEAD)$(git diff --quiet HEAD -- src pom.xml Dockerfile || echo -dirty)"
    docker build --platform linux/amd64 --build-arg GIT_SHA="$sha" -t cfp-local:latest .
    docker rm -f cfp-local >/dev/null 2>&1 || true
    # Same env as the container gets, except TLS: the Compose Postgres has none.
    docker run -d --name cfp-local --platform linux/amd64 -p 8081:8080 \
        --network "$(basename "$root")_default" \
        -e PGHOST=postgres -e PGPORT=5432 -e PGDATABASE=cfp -e PGUSER=cfp -e PGPASSWORD=cfp -e PGSSLMODE=disable \
        cfp-local:latest >/dev/null
    for _ in $(seq 1 90); do
        body=$(curl -fsS http://localhost:8081/health 2>/dev/null || true)
        if [[ $body == *"\"version\":\"$sha\""* ]]; then
            echo "$body"
            echo "image healthy on http://localhost:8081 (docker logs -f cfp-local; docker rm -f cfp-local)"
            return
        fi
        sleep 1
    done
    docker logs --tail 40 cfp-local
    echo "no /health with version $sha within 90 s" >&2
    exit 1
}

case "${1:-up}" in
    image)
        db_up
        image
        ;;
    up | restart)
        stop
        db_up
        ./mvnw -q -B package -DskipTests
        start
        ;;
    down) stop ;;
    status) curl -sS -w '  %{http_code}\n' "$url/health" || echo "not running" ;;
    logs) tail -n "${2:-100}" -f "$log" ;;
    *) sed -n '2,10p' "$0"; exit 2 ;;
esac
