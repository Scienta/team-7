#!/usr/bin/env python3
"""The 3-talk cap, before and after the submission-cap-cr change request (specs: talks, co-speakers,
submission-cap-cr).

  original:          only talks a speaker is the main speaker of count; co-speaking never uses up a place
  submission-cap-cr: main-speaker talks and co-speaker talks both count, except rejected or withdrawn ones; taking
                     someone off as co-speaker frees their place; a speaker at the cap can't submit and can't be added
                     as a co-speaker to a talk still in the running (adding them to a rejected/withdrawn one is fine);
                     the cap holds when submissions and co-speaker invitations pile in together

It works out which rules the service follows from one probe, then checks those rules, including the races. Once
submission-cap-cr is claimed on the harness (.local/feedback/latest.json), only the new rules pass; SUBMISSION_CAP=new|old
forces a mode.

Usage: workflow/bin/cap_check.py [base-url]     (default http://localhost:8080; exit 1 on any failure)
"""
import concurrent.futures
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
failures = []


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, None


def speaker():
    status, s = call("POST", "/speakers", {"name": "Cap", "email": f"cap-{uuid.uuid4().hex[:12]}@example.com"})
    assert status == 201, f"setup: POST /speakers -> {status}"
    return s["id"]


def submit(main):
    return call("POST", "/talks", {"title": "Cap", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": main})


def talk(main=None):
    """A fresh talk with its own main speaker (unless given), so the main speaker's cap never interferes."""
    status, t = submit(main or speaker())
    assert status == 201, f"setup: POST /talks -> {status}"
    return t["id"]


def co(t, s):
    return call("PUT", f"/talks/{t}/co-speakers/{s}")[0]


def decide(t, status):
    if status == "WITHDRAWN":
        owner = call("GET", f"/talks/{t}")[1]["speakerId"]
        return call("POST", f"/talks/{t}/withdraw", {"speakerId": owner})[0]
    return call("PUT", f"/talks/{t}/status", {"status": status})[0]


def check(name, ok, detail=""):
    print(("ok    " if ok else "FAIL  ") + name + (f": {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def parallel(jobs):
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(jobs)) as pool:
        return list(pool.map(lambda j: j(), jobs))


def required_mode():
    if os.environ.get("SUBMISSION_CAP") in ("new", "old"):
        return os.environ["SUBMISSION_CAP"], "SUBMISSION_CAP"
    try:
        feedback = json.load(open(".local/feedback/latest.json"))
        if any(f.get("slug") == "submission-cap-cr" and f.get("claimed") for f in feedback.get("features", [])):
            return "new", "submission-cap-cr is claimed on the harness"
    except (OSError, ValueError):
        pass
    return None, None


def old_rules():
    # co-speaking doesn't use up places, also when the own submissions arrive at once
    b = speaker()
    for _ in range(3):
        co(talk(), b)
    codes = [c for c, _ in parallel([lambda: submit(b)] * 6)]
    check("old: a co-speaker on 3 talks submits 6 at once -> exactly 3 created", codes.count(201) == 3, str(sorted(codes)))
    check("old: own talk list shows only the 3 own talks", len(call("GET", f"/speakers/{b}/talks")[1]) == 3)


def new_rules():
    # co-speaking counts: at the cap through co-speaking alone, no new talk and no new invitation
    b = speaker()
    for _ in range(3):
        co(talk(), b)
    check("new: co-speaker on 3 talks can't submit", submit(b)[0] // 100 == 4)
    code = co(talk(), b)
    check("new: co-speaker on 3 talks can't be added to a 4th talk in the running", code // 100 == 4, str(code))
    check("new: own talk list shows only own talks (none)", call("GET", f"/speakers/{b}/talks")[1] == [])

    # submissions and invitations piling in together: exactly 3 land in total
    x = speaker()
    others = [talk() for _ in range(4)]
    results = parallel([lambda: submit(x)[0]] * 4 + [lambda t=t: co(t, x) for t in others])
    landed = sum(1 for c in results if c in (200, 201))
    check("new: 4 submissions + 4 invitations at once -> exactly 3 land", landed == 3, str(results))
    check("new: the rest refused with 4xx, no 5xx", all(c in (200, 201) or c // 100 == 4 for c in results), str(results))

    # rejected and withdrawn talks don't count; a freed place can be used straight away
    y = speaker()
    own = [talk(y) for _ in range(3)]
    check("new: at the cap with 3 own talks", submit(y)[0] // 100 == 4)
    check("new: rejecting one of them works", decide(own[0], "REJECTED") == 200)
    check("new: ... frees a place straight away", submit(y)[0] == 201)
    check("new: withdrawing one works", decide(own[1], "WITHDRAWN") == 200)
    check("new: ... frees a place straight away", submit(y)[0] == 201)
    check("new: and the cap is back", submit(y)[0] // 100 == 4)

    # taking someone off as co-speaker frees their place
    z = speaker()
    talk(z), talk(z)
    t = talk()
    check("new: 2 own + 1 co-speaking: added", co(t, z) == 200)
    check("new: ... at the cap", submit(z)[0] // 100 == 4)
    check("new: removing them as co-speaker works", call("DELETE", f"/talks/{t}/co-speakers/{z}")[0] // 100 == 2)
    check("new: ... frees their place", submit(z)[0] == 201)

    # at the cap, joining a rejected or withdrawn talk is fine: it doesn't count
    w = speaker()
    for _ in range(3):
        talk(w)
    rejected, withdrawn, running = talk(), talk(), talk()
    decide(rejected, "REJECTED"), decide(withdrawn, "WITHDRAWN")
    check("new: at the cap, can be added to a rejected talk", co(rejected, w) == 200)
    check("new: at the cap, can be added to a withdrawn talk", co(withdrawn, w) == 200)
    code = co(running, w)
    check("new: at the cap, can't be added to a talk in the running", code // 100 == 4, str(code))


def main():
    # probe: a speaker co-speaking on 3 talks submits one of their own
    p = speaker()
    for _ in range(3):
        co(talk(), p)
    code = submit(p)[0]
    rules = "old" if code == 201 else "new" if code // 100 == 4 else None
    want, why = required_mode()
    print(f"submission cap: the service follows the {rules or 'neither'} rules" + (f" (required: {want}, {why})" if want else ""))
    if rules is None:
        print(f"FAIL  a co-speaker on 3 talks submitting got {code}: neither 201 (original) nor 4xx (submission-cap-cr)")
        sys.exit(1)
    if want and rules != want:
        check(f"mode: {want} rules required ({why})", False, f"the service follows the {rules} rules")
    old_rules() if rules == "old" else new_rules()
    if failures:
        print(f"{len(failures)} cap failures")
        sys.exit(1)
    print(f"CAP OK: {rules} rules")


if __name__ == "__main__":
    main()
