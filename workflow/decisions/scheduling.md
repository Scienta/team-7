# Programme scheduling: team decisions

The spec (`specs/scheduling.md`) gives the rules and the API, not the status codes or edge cases. Today's lesson
from the harness (`d093efa`): a talk that exists but is of the wrong kind or state answers `409`, never `404`; only
something that doesn't exist answers `404`. Items marked **(risk)** are guesses the harness may disagree with.

## Rooms and slots

- Exactly `R1`, `R2`, `R3` and `S1`…`S5`, written exactly like that. Anything else in the path (`R4`, `S6`, `r1`)
  is `404` for `PUT`, `GET` and `DELETE`: that room or slot doesn't exist.
- `start` / `end` come from the fixed table in the spec, as `HH:mm` (`09:00`, `13:30`).
- The long slot is `S4` (2 h). A `WORKSHOP` (120 min) fits only `S4`; every other format fits any slot, `S4`
  included.

## PUT /programme/{room}/{slot}  `{"talkId": n}`

Checked in this order; the first that applies wins, and a rejected request changes nothing.

| Case | Status |
|---|---|
| Unknown room or slot | `404` |
| Body missing or not an object, `talkId` missing or not a whole number | `400` |
| No talk with that id | `400` (harness scheduling/check-1 rejected 404: the room and slot exist, the body is wrong) |
| The talk isn't `ACCEPTED` right now (incl. `WITHDRAWN` after acceptance) | `409` |
| A `WORKSHOP` in a slot other than `S4` | `409` |
| The talk is already in this same room and slot | `200` with the `Entry`, nothing changes (idempotent; was `409`, the harness's scheduling/check-1 failed on exactly that repeat, tick 126) |
| The talk is already on the programme in another room or slot | `409` |
| Another talk is already in this room and slot | `409` |
| A person (main speaker or co-speaker) of this talk is also a person of a talk already in the same slot, in any room | `409` |
| Otherwise | `200` with the `Entry` |

- People are compared by speaker id, using each talk's main speaker and co-speakers as they are at the moment of
  the `PUT`.

## GET /programme/{room}/{slot}

- `200` with the `Entry` when a talk is scheduled there. An empty room and slot is `404` (harness scheduling/check-2
  to check-5 rejected 200 with `"talkId": null`).
- Returns what was scheduled even if the talk changed afterwards (withdrawn, new co-speakers): the spec says the
  programme stays as it is.

## DELETE /programme/{room}/{slot}

- `204`, also when the room and slot is already empty (idempotent) **(risk: could be 200 or 404)**. Afterwards
  the talk can be put somewhere else.
- Works whatever the talk's status is now.

## Concurrency

- Table `programme (room, slot, talk_id NULL)`, seeded with the 15 cells, primary key `(room, slot)`, and a unique
  index on `talk_id` (a talk is on the programme only once, also under racing PUTs into two cells).
- A `PUT` locks the three cells of its slot (`SELECT … WHERE slot = ? FOR UPDATE`) before it checks occupancy and
  people, so two committee members can't put the same speaker into two rooms of one slot, or two talks into one
  cell, at the same moment. Different slots don't block each other.
