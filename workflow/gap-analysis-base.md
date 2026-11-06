# Gap analysis: base features vs. code

2026-11-06, commit `01545b0`. Specs in `specs/`. Method: read the code against each spec line,
then probed the deployed service with curl. "Verified" = observed on the live service.

Harness at the time: every base check OK (speakers 4/4, talks 6/6, ratings 5/5), tick -999994 (pre-game).

## Gaps

| # | Feature | Spec rule | What the code does | Evidence | Fix |
|---|---|---|---|---|---|
| G1 ✅ | speakers | "needs a real email" | `SpeakerForm.email` is only `@NotBlank`; `not-an-email` → 201 | verified | validate format on POST (400) |
| G2 ✅ | speakers | bio "at most 500 characters" | `@Size(max=500)` counts UTF-16 units; 500 emoji → 400 | verified | count code points |
| G3 ✅ | ratings | reviewer is "a positive whole number" | `rating.reviewer_id` is `INTEGER`; reviewer `99999999999` → 500 "integer out of range" | verified | reject > `Integer.MAX_VALUE` with 400 (or widen column) |

Fixed 2026-11-06 (not yet deployed): G1 email regex in `Utils.isValidEmail` (POST and PUT), G2 bio counted in code
points (`Utils.characters`, POST and PUT, PUT was 1000), G3 `rating.reviewer_id` widened to BIGINT (decision: accept
any reviewer up to 2^63-1; larger → 400). Evidence: JUnit `SpeakersTest`, `RatingsTest`, `UtilsTest`; Hurl
`speakers-email-format`, `speakers-bio`, `ratings-reviewer-range`; `check.sh` all green locally.

## Holds, but fragile

| Rule | Why it works today | Risk |
|---|---|---|
| `submittedAt` `…SSS+00:00` | Jackson 2.13 `StdDateFormat` default (colon in offset) on `java.util.Date` | Jackson/Boot upgrade or `ObjectMapper` change → `Z` or no colon. Format explicitly. |
| `submittedOn` `yyyy-MM-dd` | `java.sql.Date.toString()` in JVM default TZ, built from a UTC day string | Works while both sides use the same TZ; fragile. |
| `ratedAtDisplay` `M/d/yy, h:mm AM` | JDK 11 CLDR `Locale.US` SHORT uses an ordinary space (verified bytes) | JDK 20+ emits U+202F before AM/PM. Use an explicit `DateTimeFormatter` pattern. |
| 3-talk limit under load | `SELECT … FOR UPDATE` on the speaker row + conditional insert | Verified: 8 parallel → 3×201, 5×409. |
| Rating upsert | `INSERT … ON CONFLICT` on PK `(talk_id, reviewer_id)` | Verified: 20 reviewers × 2 parallel clicks → count 20. |
| Average half-up | `BigDecimal` HALF_UP on exact sum/count | `Utils.roundRating` (floor) is wrong but only used by `/admin/stats`. |

## Verified OK

Case-insensitive email uniqueness (409), trailing-slash URLs (`/speakers/`, `/speakers/{id}/`), exact-capital
formats/levels (lowercase → 400), client can't set duration, `SUBMITTED`, oldest-first lists, unknown speaker
on POST /talks → 400, unknown talk → 404, `averageRating` null + `ratingCount` 0 on new talks, non-integer
score (`4.0`) → 400, ranking order (by code reading: rounded avg desc, count desc, id asc, unrated last).

## Team decisions to write down (spec is silent)

- Unknown `speakerId` on POST /talks: 400 today (could be 404/422).
- `GET /talks?speakerId=<unknown>` → `[]` 200, but `GET /speakers/<unknown>/talks` → 404.
- Rating validation (400) runs before the talk-exists check (404).
- Jackson coerces types: `"speakerId": "1"` and `"title": 123` are accepted.

## Outside the specs, but should fix

- ✅ `SpeakerDao.search` built SQL by string concatenation: SQL injection via `GET /speakers/search?q=` (`x' OR 1=1 --`
  listed every speaker). Now a bound parameter with LIKE wildcards escaped; `SpeakersTest.searchTreatsItsTermAsText`.
- `PUT /speakers/{id}` allows a 1000-char bio, bypassing the 500 limit.
- ✅ `addRatings` did one query per talk (N+1) on every list: 1.5 s on the deployed service at 584 talks. Now one query
  (talks LEFT JOIN rating aggregates); also `/admin/stats` and `/admin/talks.csv`. Locally at 666 talks: 0.16 s → 0.008 s,
  output byte-identical to the old build on every list endpoint.
- `server.error.include-message=always` leaks SQL in 500 responses (see G3).
- No request logging.
