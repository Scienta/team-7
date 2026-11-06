# Acceptance state machine

Version: 1
Kind: drip feature (released at tick 20)
V: 2

## Context

As the programme committee, I want to accept, reject or waitlist submitted talks, and as a speaker I want to withdraw my talk, so that the programme is built only from talks that are reviewed and still on offer.

Every talk starts as `SUBMITTED` ([Talk submissions](../talks/spec.md)), and today nothing can change that.

## What we want

- The committee decides on a talk: accept it, reject it or put it on the waitlist.
- A waitlisted talk can still be accepted or rejected later. Once a talk is accepted or rejected, the committee's decision is final.
- We only accept a talk that at least two different reviewers have rated ([Ratings](../ratings/spec.md)). A reviewer who rated it twice is still one reviewer. Rejecting and waitlisting need no ratings.
- A speaker can withdraw their own talk, unless it was already rejected or withdrawn. Nobody else can withdraw it. A withdrawn talk is out for good.
- A talk's current status shows wherever the talk is shown.
- A decision or withdrawal that isn't allowed changes nothing. The committee is several people clicking at once, so two of them deciding on the same talk must never leave it in a state the rules don't allow.

## API

```yaml
paths:
  /talks/{id}/status:
    put:
      parameters: [ { name: id, in: path, required: true, schema: { type: integer } } ]
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/Decision' }
      responses:
        '200': { content: { application/json: { schema: { $ref: '../talks/spec.md#/components/schemas/Talk' } } } }
  /talks/{id}/withdraw:
    post:
      parameters: [ { name: id, in: path, required: true, schema: { type: integer } } ]
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/Withdrawal' }
      responses:
        '200': { content: { application/json: { schema: { $ref: '../talks/spec.md#/components/schemas/Talk' } } } }
components:
  schemas:
    Status: { type: string, enum: [ SUBMITTED, ACCEPTED, REJECTED, WAITLISTED, WITHDRAWN ] }
    Decision:
      type: object
      properties:
        status: { type: string, enum: [ ACCEPTED, REJECTED, WAITLISTED ] }
    Withdrawal:
      type: object
      properties:
        speakerId: { type: integer, description: the speaker asking to withdraw }
    # Talk.status (Talk submissions spec) now takes any value of Status.
```

## Team decisions (team-7)

Not in the feature request; decided by the team and covered by `AcceptanceTest`.

Transitions (anything not listed is not allowed, including a status to itself):

| From | `PUT /status` to | `POST /withdraw` |
|---|---|---|
| `SUBMITTED` | `ACCEPTED`*, `REJECTED`, `WAITLISTED` | yes |
| `WAITLISTED` | `ACCEPTED`*, `REJECTED` | yes |
| `ACCEPTED` | none (final) | yes |
| `REJECTED` | none (final) | no |
| `WITHDRAWN` | none | no |

\* only with ratings from at least 2 distinct reviewers.

Responses:

- `200` with the Talk (new status) when the change is made.
- `400` when the body is missing or malformed, `status` isn't exactly `ACCEPTED`, `REJECTED` or `WAITLISTED`,
  or `speakerId` is missing or not a whole number.
- `404` when the talk doesn't exist.
- `403` when the withdrawing `speakerId` isn't the talk's main speaker (checked before the status).
- `409` when the transition isn't allowed from the current status, or accepting a talk with fewer than 2 reviewers.
- A rejected request changes nothing.

Concurrency: each change is one conditional `UPDATE … WHERE status IN (<allowed from>)` (plus the reviewer count for
`ACCEPTED`, and the main speaker for a withdrawal), so of two conflicting concurrent decisions exactly one wins and
the other gets `409`.
