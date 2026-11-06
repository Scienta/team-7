#!/usr/bin/env bash
# Runs the app locally the way it runs deployed: the root Dockerfile (its JDK, not whatever is installed here),
# against the Postgres from docker-compose.yml. No local JDK needed.
#
# Usage: workflow/bin/local.sh up       build the working tree (native arch, fast) and (re)start it on :8080
#        workflow/bin/local.sh restart  same as up
#        workflow/bin/local.sh down     stop it (Postgres keeps running; `docker compose down` stops that)
#        workflow/bin/local.sh status   health of the running app
#        workflow/bin/local.sh logs     follow the app log
#        workflow/bin/local.sh image    deploy rehearsal: build exactly like deploy.yml (linux/amd64, GIT_SHA baked
#                                       in, slower), run on :8081, require /health to report that SHA like rollout.sh
#        workflow/bin/local.sh ref <commit>
#                                       build <commit> and run it on :8082, the "before" side for compare.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
mkdir -p .local
network="$(basename "$root")_default"

db_up() {
    docker compose up -d postgres >/dev/null
    for _ in $(seq 1 30); do
        docker compose exec -T postgres pg_isready -U cfp -q && return
        sleep 1
    done
    echo "postgres didn't become ready" >&2
    exit 1
}

# run <container> <port> <image> <expected version>
run() {
    docker rm -f "$1" >/dev/null 2>&1 || true
    # Same env as the deployed container gets, except TLS: the Compose Postgres has none.
    docker run -d --name "$1" -p "$2:8080" --network "$network" \
        -e PGHOST=postgres -e PGPORT=5432 -e PGDATABASE=cfp -e PGUSER=cfp -e PGPASSWORD=cfp -e PGSSLMODE=disable \
        "$3" >/dev/null
    for _ in $(seq 1 90); do
        body=$(curl -fsS "http://localhost:$2/health" 2>/dev/null || true)
        if [[ $body == *"\"version\":\"$4\""* ]]; then
            echo "$body  on http://localhost:$2"
            return
        fi
        sleep 1
    done
    docker logs --tail 40 "$1"
    echo "no /health with version $4 on :$2 within 90 s" >&2
    exit 1
}

case "${1:-up}" in
    up | restart)
        db_up
        sha="$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- src pom.xml Dockerfile || echo -dirty)"
        docker build -q -t cfp-dev --build-arg GIT_SHA="$sha" . >/dev/null
        run cfp-dev 8080 cfp-dev "$sha"
        ;;
    down) docker rm -f cfp-dev >/dev/null 2>&1 && echo stopped || true ;;
    status) curl -sS -w '  %{http_code}\n' http://localhost:8080/health || echo "not running" ;;
    logs) docker logs -f --tail "${2:-100}" cfp-dev ;;
    image)
        db_up
        sha="$(git rev-parse HEAD)$(git diff --quiet HEAD -- src pom.xml Dockerfile || echo -dirty)"
        docker build --platform linux/amd64 -t cfp-image --build-arg GIT_SHA="$sha" .
        run cfp-image 8081 cfp-image "$sha"
        ;;
    ref)
        db_up
        ref=$(git rev-parse --short "${2:?usage: local.sh ref <commit>}")
        if ! docker image inspect "cfp-ref:$ref" >/dev/null 2>&1; then
            rm -rf .local/ref && git worktree prune
            git worktree add -q --detach .local/ref "$ref"
            docker build -q -t "cfp-ref:$ref" --build-arg GIT_SHA="$ref" .local/ref >/dev/null
        fi
        run cfp-ref 8082 "cfp-ref:$ref" "$ref"
        ;;
    *) sed -n '2,15p' "$0"; exit 2 ;;
esac
