# Co-speakers

Version: 1
Kind: drip feature (released at tick 0)
V: 1

## Context

As a speaker, I want to add up to two co-speakers to my talk, so that the programme shows everyone who presents it.

## What we want

- A talk keeps its one main speaker (`speakerId`, [Talk submissions](../talks/spec.md)). Co-speakers are other registered speakers ([Speakers](../speakers/spec.md)) added to the talk afterwards, and can be removed again.
- A talk has at most two co-speakers. The main speaker can't also be a co-speaker of their own talk.
- Every talk shows its co-speakers, in the order they were added. Adding someone who is already a co-speaker changes nothing.
- A speaker's own talk list still shows only the talks where they are the main speaker.
- Co-speaking doesn't use up a speaker's own submissions: the limit of 3 talks still counts only the talks where they are the main speaker, and a speaker who has reached it can still co-speak on other talks.

## API

```yaml
paths:
  /talks/{id}/co-speakers/{speakerId}:
    parameters:
      - { name: id, in: path, required: true, schema: { type: integer } }
      - { name: speakerId, in: path, required: true, schema: { type: integer } }
    put:
      description: no request body
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/Talk' } } } }
    delete:
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/Talk' } } } }
components:
  schemas:
    Talk:
      description: the Talk of the Talk submissions spec, with one more field
      type: object
      properties:
        coSpeakerIds:
          type: array
          items: { type: integer }
          description: the co-speakers' speaker ids, in the order they were added; [] when none
```

## Team decisions (team-7)

Not in the feature request; decided by the team and covered by `CoSpeakersTest` and `examples/co-speakers.hurl`.

Responses, checked in this order (talk, speaker, main speaker, already a co-speaker, limit):

- `200` with the Talk when the change is made, and also when adding someone who already is a co-speaker (even when
  the talk is full; they keep their place).
- `400` when an id in the path isn't a whole number, or when adding the talk's main speaker.
- `404` when the talk doesn't exist, `speakerId` isn't a registered speaker (a path id, unlike `POST /talks`, which
  answers `400` for an unknown `speakerId` in the body), or, on `DELETE`, isn't a co-speaker of the talk (including
  the main speaker, and removing the same co-speaker twice).
- `409` when adding a third distinct co-speaker (the same code as the 3-talk limit).

Order: a co-speaker who is removed and added again goes to the end of the list. `GET /talks?speakerId=` lists only
talks where that speaker is the main speaker, like `/speakers/{id}/talks`.

Co-speakers can be changed whatever the talk's status (Acceptance), and only the main speaker can withdraw a talk.

Concurrency: adding and removing lock the talk's row (`SELECT … FOR UPDATE`) before counting, so concurrent additions
can't go past two; a unique `(talk_id, speaker_id)` with `ON CONFLICT DO NOTHING` covers double-clicks. With the lock
removed, `CoSpeakersTest.concurrentAdditionsNeverExceedTwoCoSpeakers` failed 2 runs out of 3.

Corrected after the claim (tick 48): we first answered `409` for the main speaker and `200` for removing a
non-co-speaker. The harness failed `co-speakers/check-2` on `PUT` of the main speaker and `co-speakers/check-1` on a
second `DELETE` of the same co-speaker. It shows the failing request, not the expected status: `400` and `404` are our
best reading (400 matches the base app's other invalid requests). Still a guess: `409` for a third co-speaker, and
`404` for an unknown speaker on `PUT` (check-2 stopped before reaching those).
