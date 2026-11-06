#!/usr/bin/env python3
"""Warm up a freshly deployed revision before the harness's next burst of checks.

A new JVM hasn't compiled its hot paths yet, and on the platform's small CPU the first burst of harness traffic
after a deploy took up to 5 s per request (ticks 100 and 103 timed out). This sends rounds of parallel requests
along the same paths, but only requests that can't change data:

  - reads: health, info, talk lists and rankings, single talks, speakers, workshop registrations;
  - writes the service rejects before writing anything: a speaker with an invalid email (400), a talk without a
    speakerId (400, after the title/format/level checks), an unknown decision status (400), a withdrawal without a
    speakerId (400), a score of 9 (400), a workshop registration without a name (400), and removing a talk's own main
    speaker as a co-speaker (404: the main speaker can never be a co-speaker, so the DELETE matches no row).

Prints each round's status counts and latencies, so the log shows the warm-up taking effect. Always exits 0: it's a
best effort, never a reason to fail anything.

Usage: workflow/bin/warmup.py <base-url> [--rounds N] [--parallel N] [--seconds N]
"""
import argparse
import concurrent.futures
import json
import random
import time
import urllib.error
import urllib.request


def call(base, method, path, body=None, timeout=15):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", "User-Agent": "cfp-warmup"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            res.read()
            status = res.status
    except urllib.error.HTTPError as e:
        e.read()
        status = e.code
    except Exception:  # timeout, connection reset: count it, carry on
        status = 0
    return status, (time.monotonic() - started) * 1000


def requests_for(talks):
    """One round: every safe request, once per sampled talk (with its main speaker)."""
    out = [("GET", "/health", None), ("GET", "/actuator/info", None),
           ("GET", "/talks", None), ("GET", "/talks?sort=rating", None)]
    for talk_id, speaker_id in talks:
        out += [
            ("GET", f"/talks/{talk_id}", None),
            ("GET", f"/talks?speakerId={speaker_id}", None),
            ("GET", f"/talks?speakerId={speaker_id}&sort=rating", None),
            ("GET", f"/speakers/{speaker_id}", None),
            ("GET", f"/speakers/{speaker_id}/", None),
            ("GET", f"/speakers/{speaker_id}/talks", None),
            ("GET", f"/workshops/{talk_id}/registrations", None),
            ("POST", "/speakers", {"name": "Warm Up", "email": "not-an-email"}),
            ("POST", "/speakers/", {"name": "Warm Up", "email": "not-an-email"}),
            ("POST", "/talks", {"title": "Warm up", "format": "WORKSHOP", "level": "BEGINNER"}),
            ("PUT", f"/talks/{talk_id}/status", {"status": "WARMUP"}),
            ("POST", f"/talks/{talk_id}/withdraw", {}),
            ("PUT", f"/talks/{talk_id}/ratings/1", {"score": 9}),
            ("POST", f"/workshops/{talk_id}/registrations", {}),
            ("DELETE", f"/talks/{talk_id}/co-speakers/{speaker_id}", None),
        ]
    return out


def pct(values, q):
    values = sorted(values)
    return values[min(len(values) - 1, int(q * len(values)))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("--rounds", type=int, default=6)
    ap.add_argument("--parallel", type=int, default=12)
    ap.add_argument("--seconds", type=int, default=90, help="stop after this long, whatever the rounds")
    args = ap.parse_args()
    base = args.base.rstrip("/")
    deadline = time.monotonic() + args.seconds

    try:
        with urllib.request.urlopen(urllib.request.Request(base + "/talks", headers={"User-Agent": "cfp-warmup"}),
                                    timeout=30) as res:
            all_talks = json.load(res)
        talks = [(t["id"], t["speakerId"]) for t in all_talks if t.get("id") and t.get("speakerId")]
    except Exception as e:
        print(f"::warning::warm-up: couldn't list talks ({e}); warming up with reads of /health and /talks only")
        talks = []
    talks = random.sample(talks, min(len(talks), 10))

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.parallel) as pool:
        for n in range(1, args.rounds + 1):
            if time.monotonic() > deadline:
                print(f"warm-up: stopping after {args.seconds} s")
                break
            batch = requests_for(talks)
            random.shuffle(batch)
            results = list(pool.map(lambda r: call(base, *r), batch))
            statuses = {}
            for status, _ in results:
                statuses[status] = statuses.get(status, 0) + 1
            ms = [m for _, m in results]
            print(f"round {n}: {len(ms)} requests, p50 {pct(ms, .5):.0f} ms, p95 {pct(ms, .95):.0f} ms, "
                  f"max {max(ms):.0f} ms, statuses {dict(sorted(statuses.items()))}")
            if statuses.get(0) or any(s >= 500 for s in statuses):
                print("::warning::warm-up: some requests got no answer or a 5xx (see the statuses above)")


if __name__ == "__main__":
    main()
