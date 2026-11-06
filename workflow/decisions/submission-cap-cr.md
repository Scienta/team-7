# Submission cap change request: team decisions

The change request (`specs/submission-cap-cr.md`) changes the rule, not the API. These are ours, consistent with
the co-speaker decisions in `specs/co-speakers.md` (team section) and the talks limit.

## What counts

- A speaker's count = talks where they are the main speaker **or** a co-speaker, whose status is not `REJECTED`
  or `WITHDRAWN`. `SUBMITTED`, `WAITLISTED` and `ACCEPTED` count.
- The limit is 3: a new talk or a new co-speaking place is allowed only while the count is below 3.
- Places free up by themselves: a rejection, a withdrawal or taking someone off as co-speaker lowers the count at
  once; nothing else changes.
- Over the limit already (from before the change): everything stays, nothing new until below 3.

## POST /talks

- Unchanged order and codes; at the limit `409` (as today). The count now includes co-speaking.

## PUT /talks/{id}/co-speakers/{speakerId}

Checked in this order (the existing ones unchanged):

| Case | Status |
|---|---|
| No such talk / no such speaker | `404` |
| The talk's main speaker | `400` |
| Already a co-speaker of this talk | `200`, nothing changes, **even at the limit** (they keep their place) |
| The talk already has 2 co-speakers | `409` |
| The talk is `REJECTED` or `WITHDRAWN` | `200`: it doesn't count, so no limit check |
| The speaker is at the limit (count ≥ 3) | `409` |
| Otherwise | `200` |

## Concurrency

- Both paths lock the speaker's row (`SELECT … FROM speaker WHERE id = ? FOR UPDATE`) before counting, so a
  speaker's submissions and co-speaker invitations queue up behind each other and the count can't be overtaken.
- `addCoSpeaker` locks the talk first, then the speaker. Nothing locks a speaker and then a talk, so no deadlock.
- Status changes and removals only lower the count; they don't need the speaker lock.

## Lists

- `GET /speakers/{id}/talks` and `GET /talks?speakerId=` still show only talks where the speaker is the main
  speaker.

## Not changed by this request

- A talk still has at most 2 co-speakers, whatever its status: adding a third to a rejected or withdrawn talk is
  still `409`. "Being added to a rejected or withdrawn talk is fine" is about the speaker's own limit, not the
  talk's.
