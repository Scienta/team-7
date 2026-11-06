# Change request: ratings

Version: 1
Kind: change request (released at tick 167)
V: 2

## Context

As the programme committee, I want a talk's average to ignore the single most enthusiastic and the single harshest reviewer once enough reviewers have rated it, and to show two decimals, so that one outlier can't decide the ranking and close talks are told apart.

## What changes

This changes how [Ratings](../ratings/spec.md) calculates `averageRating`, and so the ranking of `GET /talks?sort=rating`. Everything else about ratings stays as it is.

- Once a talk has 5 or more ratings, its average leaves out one highest and one lowest score: exactly one of each, even when several reviewers gave the same top or bottom score. With fewer than 5 ratings, every score counts, as before.
- The average is rounded to 2 decimals. Ties at the third decimal go to the even neighbour (banker's rounding): `3.125` becomes `3.12` and `3.375` becomes `3.38`.
- `ratingCount` still counts every reviewer, including the ones left out of the average.
- The ranking uses the new, rounded average.

## API

No new endpoints. The changed field on `Talk` ([Talk submissions](../talks/spec.md), [Ratings](../ratings/spec.md)):

```yaml
components:
  schemas:
    # averageRating: { type: number, nullable: true }   # now trimmed at 5+ ratings and rounded to 2 decimals
```
