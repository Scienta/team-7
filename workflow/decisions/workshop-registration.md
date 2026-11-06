# Workshop registration: team decisions

The spec (`specs/workshop-registration.md`) gives the rules and the API, not the status codes or edge cases.
These are ours.

## Which talks are workshops

- A "workshop" is a talk with format `WORKSHOP`. Any other id, an existing non-workshop talk or a talk that
  doesn't exist, is `404` for both `POST` and `GET /workshops/{id}/registrations`.

## POST /workshops/{id}/registrations

Checked in this order; the first that applies wins, and a rejected request changes nothing.

| Case | Status |
|---|---|
| No talk with that id, or the talk isn't a `WORKSHOP` | `404` |
| Body missing or not a JSON object; `name` missing, not a string or blank; `email` missing, not a string, or not an email (same rule as speaker registration: `local@domain.tld`) | `400` |
| The workshop isn't `ACCEPTED` right now (`SUBMITTED`, `WAITLISTED`, `REJECTED`, or `WITHDRAWN`, also after it was accepted) | `409` |
| That email (case-insensitive) is already registered for this workshop | `409` |
| The workshop already has 20 registrations | `409` |
| Otherwise | `201` with the `Registration` |

- `name` and `email` are stored and returned exactly as sent (no trimming, no lower-casing).
- The same email may register for any number of different workshops.

## GET /workshops/{id}/registrations

- `200` with every registration for a `WORKSHOP` talk, in the order they were made (oldest first), whatever the
  workshop's status now: a workshop withdrawn after people registered still lists them. `[]` when there are none.

## Concurrency

- Registration locks the workshop's talk row (`SELECT … FOR UPDATE`) before it checks the status, the duplicate
  and the count, so a rush can never put more than 20 on the list, and a withdrawal racing a registration is seen
  either before or after it, never half-way.
- A unique index on `(talk_id, lower(email))` backs up "one registration per email per workshop".
