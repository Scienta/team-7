# Programme scheduling

Version: 1
Kind: drip feature (released at tick 100)
V: 3

## Context

As the programme committee, I want to put accepted talks into rooms and time slots, so that the conference gets a programme where every talk fits its slot and nobody has to be in two rooms at once.

## What we want

- The venue is fixed: three rooms, `R1`, `R2` and `R3`, and the same five slots in every room. Nobody can add or change rooms or slots.

  | Slot | Start | End |
  |---|---|---|
  | `S1` | 09:00 | 10:00 |
  | `S2` | 10:15 | 11:15 |
  | `S3` | 11:30 | 12:30 |
  | `S4` | 13:30 | 15:30 |
  | `S5` | 15:45 | 16:45 |

  Times are written as on the signs, `HH:mm` on a 24-hour clock (`09:00`, not `9:00`).
- The committee puts a talk in a room and slot, looks up what's in it, and takes the talk out again. A talk that's been taken out can go somewhere else.
- Only accepted talks ([Acceptance state machine](../acceptance/spec.md)) go on the programme.
- A talk must fit its slot: a workshop needs the long slot, everything else fits anywhere.
- One talk per room and slot, and a talk is on the programme only once. To move a talk, take it out first.
- Nobody speaks in two rooms at the same time: the main speaker and the co-speakers ([Co-speakers](../co-speakers/spec.md)) of talks in the same slot must all be different people.
- We check the rules when a talk is put on the programme. If a talk changes afterwards, the programme stays as it is.
- Several committee members work on the programme at once, and it must never end up breaking the rules.

## API

```yaml
paths:
  /programme/{room}/{slot}:
    parameters:
      - { name: room, in: path, required: true, schema: { $ref: '#/components/schemas/Room' } }
      - { name: slot, in: path, required: true, schema: { $ref: '#/components/schemas/Slot' } }
    put:
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/Scheduling' }
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/Entry' } } } }
    get:
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/Entry' } } } }
    delete:
      description: takes the talk out of this room and slot
components:
  schemas:
    Room: { type: string, enum: [ R1, R2, R3 ] }
    Slot: { type: string, enum: [ S1, S2, S3, S4, S5 ] }
    Scheduling:
      type: object
      properties:
        talkId: { type: integer }
    Entry:
      type: object
      properties:
        room: { $ref: '#/components/schemas/Room' }
        slot: { $ref: '#/components/schemas/Slot' }
        start: { type: string, example: '13:30' }
        end: { type: string, example: '15:30' }
        talkId: { type: integer }
```
