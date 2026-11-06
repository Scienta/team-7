# Programme export: team decisions

The spec (harness `/specs/programme-export/spec.md`, version 1) gives the two views and the CSV format, not every
edge case. What we decided. Items marked **(risk)** are guesses the harness may disagree with.

## What's in it

- One row per room and slot that has a talk (`programme.talk_id` not null); empty cells don't appear.
- Every talk on the programme appears, whatever its status is now (a talk withdrawn after it was scheduled is still
  listed): Programme scheduling says the programme stays as it is when a talk changes afterwards. **(risk)**
- Title, format and speakers are the talk's **current** ones ("as they are right now"), read when the export is asked
  for, not when the talk was scheduled.
- Speakers by name: the main speaker first, then the co-speakers in the order they were added (`talk_co_speaker.seq`).
- `start` and `end` are the slot's, `HH:mm`, from the same table as Programme scheduling (`09:00`, `13:30`).

## Order

- By slot start (`S1` 09:00 … `S5` 15:45), then by room (`R1`, `R2`, `R3`). The slots' starts are all different, so
  this is "earliest start first, within a slot by room". JSON and CSV in the same order.

## GET /programme (JSON)

- `200`, an array of `{slot, start, end, room, talkId, title, format, speakers}` in that field order; `[]` when the
  programme is empty. `GET /programme/` (trailing slash) is the same.

## GET /programme.csv

- `200`, `Content-Type: text/csv;charset=UTF-8`, whatever the request's `Accept` says (`application/json` included:
  never `406`). No `Content-Disposition`. UTF-8 without a BOM.
- First line exactly `slot,start,end,room,talkId,title,format,speakers`, then one line per row, same fields.
- Every line ends with `\n` (no `\r\n`), the last one too, and there's no blank line after it. An empty programme is
  just the header line (with its `\n`).
- A field is quoted only if it contains a comma, a double quote, `\r` or `\n`; inside quotes a `"` is doubled. Every
  other field is written as it is (spaces and semicolons included).
- `speakers` is the names joined with `"; "` (semicolon, space); a name with a comma makes the field quoted.

## Consistency

- Both views read the programme, talks and names in one SQL statement, so a row never mixes states from concurrent
  changes (one snapshot).
