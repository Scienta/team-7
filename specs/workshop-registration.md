# Workshop registration with capacity

Version: 1
Kind: drip feature (released at tick 67)
V: 2

## Context

As an attendee, I want to register for an accepted workshop, and as the organisers we want each workshop to take no more attendees than its room holds, so that nobody turns up to a workshop that has no seat for them.

## What we want

- Attendees register for a workshop with their name and email. Both are required.
- Only workshops (talks with format `WORKSHOP`, [Talk submissions](../talks/spec.md)) that the committee has accepted ([Acceptance state machine](../acceptance/spec.md)) take registrations. If a workshop is withdrawn later, the people already registered stay on its list.
- Every workshop has room for 20 attendees, and never more. Registration opens to everyone at the same moment, so expect a rush.
- One registration per email per workshop. People type their email in all sorts of ways: `ada@example.com` and `ADA@Example.com` are the same person. The same person can register for several workshops.
- The organisers can see who's registered for a workshop, in the order they registered.

## API

```yaml
paths:
  /workshops/{id}/registrations:
    parameters: [ { name: id, in: path, required: true, description: the workshop's talk id, schema: { type: integer } } ]
    post:
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RegistrationRequest' }
      responses:
        '201': { content: { application/json: { schema: { $ref: '#/components/schemas/Registration' } } } }
    get:
      responses:
        '200':
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/Registration' } }
components:
  schemas:
    RegistrationRequest:
      type: object
      properties:
        name: { type: string }
        email: { type: string }
    Registration:
      type: object
      properties:
        id: { type: integer }
        workshopId: { type: integer }
        name: { type: string }
        email: { type: string, description: as the attendee typed it }
```
