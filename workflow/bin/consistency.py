#!/usr/bin/env python3
"""Consistency matrix: every endpoint that takes a talk id, against every kind of talk.

The harness has failed us three times today on the same kind of bug: a talk that exists, but is of an unusual kind
or in an unusual state, answered 404 "not found" on one endpoint while another endpoint treated it as existing.
Our per-feature suites only test the combinations someone thought of. This tries all of them.

For each kind of talk (every format x every status) and a talk id that doesn't exist, it calls every talk endpoint
with a valid body, each call on a fresh talk (so a mutating call can't change the next one's input), and checks:

  1. a talk that exists never gets 404 (a refusal is another 4xx: 400, 403, 409, ...), except where the 404 is
     about a sub-resource and the harness has confirmed it (EXPECT_404)
  2. a talk that doesn't exist gets 404 on every endpoint
  3. no 5xx anywhere

Usage: workflow/bin/consistency.py [base-url]      (default http://localhost:8080)
Prints the matrix and every violation; exit 1 on any violation.
"""
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
FORMATS = ["LIGHTNING_10", "LIGHTNING_20", "PRESENTATION", "WORKSHOP"]
# How to reach each status from SUBMITTED: the committee decisions and withdrawals, in order.
STATUSES = {
    "SUBMITTED": [],
    "WAITLISTED": [("status", "WAITLISTED")],
    "ACCEPTED": [("status", "ACCEPTED")],
    "REJECTED": [("status", "REJECTED")],
    "WITHDRAWN": [("withdraw", None)],
}
MISSING = 999999999


def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, None


def speaker():
    status, body = call("POST", "/speakers", {"name": "Matrix", "email": f"matrix-{uuid.uuid4().hex[:12]}@example.com"})
    assert status == 201, f"setup: POST /speakers -> {status}"
    return body["id"]


def talk(fmt, status):
    """A fresh talk of this format in this status, with two reviewers so it can be accepted. Returns (talk, owner)."""
    owner = speaker()
    code, body = call("POST", "/talks", {"title": "Matrix talk", "format": fmt, "level": "BEGINNER", "speakerId": owner})
    assert code == 201, f"setup: POST /talks {fmt} -> {code}"
    t = body["id"]
    call("PUT", f"/talks/{t}/ratings/1", {"score": 4})
    call("PUT", f"/talks/{t}/ratings/2", {"score": 5})
    for kind, value in STATUSES[status]:
        if kind == "status":
            code, _ = call("PUT", f"/talks/{t}/status", {"status": value})
        else:
            code, _ = call("POST", f"/talks/{t}/withdraw", {"speakerId": owner})
        assert code == 200, f"setup: moving a {fmt} talk to {status} -> {code}"
    return t, owner


# Expectations the harness has confirmed that override rule 1: the 404 is about the sub-resource, not the talk.
# Removing someone who isn't a co-speaker is 404 (co-speakers/check-1 turned green with exactly that, c8929ce).
EXPECT_404 = {"DELETE co-speaker (isn't)"}
# Set-up a column needs on its fresh talk before the call.
SETUP = {"DELETE co-speaker (is one)": lambda t, o, x: call("PUT", f"/talks/{t}/co-speakers/{x}")}

# Every endpoint with a talk id in its path, with a body that is valid on its own. `t` is the talk, `o` its owner,
# `x` another registered speaker.
ENDPOINTS = [
    ("GET /talks/{id}", lambda t, o, x: ("GET", f"/talks/{t}", None)),
    ("PUT ratings", lambda t, o, x: ("PUT", f"/talks/{t}/ratings/7", {"score": 3})),
    ("PUT status ACCEPTED", lambda t, o, x: ("PUT", f"/talks/{t}/status", {"status": "ACCEPTED"})),
    ("PUT status REJECTED", lambda t, o, x: ("PUT", f"/talks/{t}/status", {"status": "REJECTED"})),
    ("PUT status WAITLISTED", lambda t, o, x: ("PUT", f"/talks/{t}/status", {"status": "WAITLISTED"})),
    ("POST withdraw (owner)", lambda t, o, x: ("POST", f"/talks/{t}/withdraw", {"speakerId": o})),
    ("POST withdraw (other)", lambda t, o, x: ("POST", f"/talks/{t}/withdraw", {"speakerId": x})),
    ("PUT co-speaker", lambda t, o, x: ("PUT", f"/talks/{t}/co-speakers/{x}", None)),
    ("DELETE co-speaker (is one)", lambda t, o, x: ("DELETE", f"/talks/{t}/co-speakers/{x}", None)),
    ("DELETE co-speaker (isn't)", lambda t, o, x: ("DELETE", f"/talks/{t}/co-speakers/{x}", None)),
    ("GET registrations", lambda t, o, x: ("GET", f"/workshops/{t}/registrations", None)),
    ("POST registration", lambda t, o, x: ("POST", f"/workshops/{t}/registrations",
                                           {"name": "Ada", "email": f"ada-{uuid.uuid4().hex[:12]}@example.com"})),
]


def main():
    other = speaker()
    rows, violations = [], []
    for fmt in FORMATS:
        for status in STATUSES:
            row = []
            for name, make in ENDPOINTS:
                t, owner = talk(fmt, status)
                if name in SETUP:
                    SETUP[name](t, owner, other)
                method, path, body = make(t, owner, other)
                code, _ = call(method, path, body)
                row.append(code)
                if name in EXPECT_404:
                    if code != 404:
                        violations.append(f"{fmt} {status}: {name} -> {code}, the harness expects 404")
                elif code == 404:
                    violations.append(f"{fmt} {status}: {name} -> 404, but the talk exists")
                if code >= 500:
                    violations.append(f"{fmt} {status}: {name} -> {code}")
            rows.append((f"{fmt} {status}", row))
    row = []
    for name, make in ENDPOINTS:
        method, path, body = make(MISSING, other, other)
        code, _ = call(method, path, body)
        row.append(code)
        if code != 404:
            violations.append(f"no such talk: {name} -> {code}, expected 404")
    rows.append(("no such talk", row))

    width = max(len(n) for n, _ in ENDPOINTS)
    print(f"{'':24}" + " ".join(f"{i + 1:>4}" for i in range(len(ENDPOINTS))))
    for label, codes in rows:
        print(f"{label:24}" + " ".join(f"{c:>4}" for c in codes))
    print("columns: " + "; ".join(f"{i + 1} {n}" for i, (n, _) in enumerate(ENDPOINTS)))
    if violations:
        print(f"\n{len(violations)} violations:")
        for v in violations:
            print("  FAIL  " + v)
        sys.exit(1)
    print(f"\nCONSISTENT: {len(rows)} kinds of talk x {len(ENDPOINTS)} endpoints, no 404 for an existing talk, "
          "404 everywhere for a missing one, no 5xx")


if __name__ == "__main__":
    main()
