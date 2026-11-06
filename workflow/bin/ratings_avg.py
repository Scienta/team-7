#!/usr/bin/env python3
"""The averaging rules of ratings, before and after the ratings-cr change request (specs: ratings, ratings-cr).

  original:   every score counts; 1 decimal, half up (4.25 -> 4.3)
  ratings-cr: with 5 or more ratings, exactly one highest and one lowest score are left out; 2 decimals, half even
              (3.125 -> 3.12, 3.375 -> 3.38); ratingCount still counts every reviewer; the ranking uses it

It works out which rules the service follows from one probe, then checks every case under those rules, so a half
implementation (trimming without the new rounding, say) fails either way. Once ratings-cr is claimed on the harness
(.local/feedback/latest.json, kept fresh by feedback.sh and watch.sh), only the new rules pass; RATINGS_CR=new|old
forces a mode.

Usage: workflow/bin/ratings_avg.py [base-url]     (default http://localhost:8080; exit 1 on any failure)
"""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from decimal import ROUND_HALF_EVEN, ROUND_HALF_UP, Decimal
from fractions import Fraction

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, None


def rounded(fraction, places, mode):
    exact = Decimal(fraction.numerator) / Decimal(fraction.denominator)  # enough digits for these fractions
    return float(exact.quantize(Decimal(1).scaleb(-places), rounding=mode))


def expected(scores, rules):
    if rules == "old":
        return rounded(Fraction(sum(scores), len(scores)), 1, ROUND_HALF_UP)
    kept = sorted(scores)
    if len(kept) >= 5:
        kept = kept[1:-1]  # exactly one lowest and one highest, even when tied
    return rounded(Fraction(sum(kept), len(kept)), 2, ROUND_HALF_EVEN)


def talk_with(scores):
    """A fresh talk (own speaker, so the 3-talk limit never interferes) rated by len(scores) reviewers."""
    _, s = call("POST", "/speakers", {"name": "Avg", "email": f"avg-{uuid.uuid4().hex[:12]}@example.com"})
    status, t = call("POST", "/talks", {"title": "Avg", "format": "PRESENTATION", "level": "BEGINNER",
                                        "speakerId": s["id"]})
    assert status == 201, f"setup: POST /talks -> {status}"
    for reviewer, score in enumerate(scores, 1):
        assert call("PUT", f"/talks/{t['id']}/ratings/{reviewer}", {"score": score})[0] == 200
    return t["id"], s["id"]


def required_mode():
    if os.environ.get("RATINGS_CR") in ("new", "old"):
        return os.environ["RATINGS_CR"], "RATINGS_CR"
    try:
        feedback = json.load(open(".local/feedback/latest.json"))
        if any(f.get("slug") == "ratings-cr" and f.get("claimed") for f in feedback.get("features", [])):
            return "new", "ratings-cr is claimed on the harness"
    except (OSError, ValueError):
        pass
    return None, None


CASES = [
    ("3 ratings: 2 decimals", [4, 4, 5]),
    ("4 ratings: 2 decimals", [4, 4, 4, 5]),
    ("4 ratings, whole", [3, 3, 3, 3]),
    ("5 ratings: one top and one bottom left out", [1, 4, 4, 4, 5]),
    ("tied extremes: exactly one of each left out", [5, 5, 5, 1, 1]),
    ("10 ratings, kept average 25/8: half even down", [1, 5, 3, 3, 3, 3, 3, 3, 3, 4]),
    ("10 ratings, kept average 27/8: half even up", [1, 5, 3, 3, 3, 3, 3, 4, 4, 4]),
]


def main():
    failures = []

    def check(name, got, want):
        ok = got is not None and abs(got - want) < 1e-9 if want is not None else got is None
        print(("ok    " if ok else "FAIL  ") + f"{name}: {got} (expected {want})")
        if not ok:
            failures.append(name)

    probe, _ = talk_with([4, 4, 5])
    shown = call("GET", f"/talks/{probe}")[1]["averageRating"]
    rules = "new" if shown == expected([4, 4, 5], "new") else "old" if shown == expected([4, 4, 5], "old") else None
    want_mode, why = required_mode()
    print(f"ratings averaging: the service follows the {rules or 'neither'} rules"
          + (f" (required: {want_mode}, {why})" if want_mode else ""))
    if rules is None:
        print(f"FAIL  4, 4, 5 averages to {shown}: neither 4.3 (original) nor 4.33 (ratings-cr)")
        sys.exit(1)
    if want_mode and rules != want_mode:
        failures.append(f"mode: {rules} rules, but {want_mode} required ({why})")
        print(f"FAIL  {failures[-1]}")

    for name, scores in CASES:
        t, s = talk_with(scores)
        talk = call("GET", f"/talks/{t}")[1]
        check(f"{name} {scores}", talk["averageRating"], expected(scores, rules))
        check(f"{name}: ratingCount counts every reviewer", talk["ratingCount"], len(scores))

    # Shown the same wherever a talk is shown, and recalculated when a reviewer changes their score.
    scores = [1, 4, 4, 4, 5]
    t, s = talk_with(scores)
    want = expected(scores, rules)
    check("speaker's talk list", call("GET", f"/speakers/{s}/talks")[1][0]["averageRating"], want)
    check("talk list by speaker", call("GET", f"/talks?speakerId={s}")[1][0]["averageRating"], want)
    check("ranking by speaker", call("GET", f"/talks?speakerId={s}&sort=rating")[1][0]["averageRating"], want)
    call("PUT", f"/talks/{t}/ratings/1", {"score": 5})
    check("re-rating 1 -> 5 recalculates [5, 4, 4, 4, 5]", call("GET", f"/talks/{t}")[1]["averageRating"],
          expected([5, 4, 4, 4, 5], rules))

    # The ranking uses the average of the active rules: P and Q swap places between the two.
    _, s = call("POST", "/speakers", {"name": "Rank", "email": f"rank-{uuid.uuid4().hex[:12]}@example.com"})
    ids = {}
    for name, sc in (("P", [1, 5, 5, 5, 5]), ("Q", [4, 4, 5, 5, 5])):
        _, t = call("POST", "/talks", {"title": name, "format": "PRESENTATION", "level": "BEGINNER", "speakerId": s["id"]})
        ids[t["id"]] = (name, sc)
        for reviewer, score in enumerate(sc, 1):
            call("PUT", f"/talks/{t['id']}/ratings/{reviewer}", {"score": score})
    ranked = [ids[x["id"]][0] for x in call("GET", f"/talks?speakerId={s['id']}&sort=rating")[1]]
    want_order = sorted(ids.values(), key=lambda v: -expected(v[1], rules))
    check_order = [n for n, _ in want_order]
    ok = ranked == check_order
    print(("ok    " if ok else "FAIL  ") + f"ranking P {ids and [1, 5, 5, 5, 5]} vs Q [4, 4, 5, 5, 5]: {ranked} "
          f"(expected {check_order} under the {rules} rules)")
    if not ok:
        failures.append("ranking")

    if failures:
        print(f"{len(failures)} averaging failures")
        sys.exit(1)
    print(f"AVERAGING OK: {rules} rules, {len(CASES) * 2 + 5} checks")


if __name__ == "__main__":
    main()
