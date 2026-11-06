# Change request: submission cap

Version: 1
Kind: change request (released at tick 180)
V: 2

## Context

As the programme committee, I want the cap of 3 talks per speaker to count every talk a speaker presents, as main speaker or as co-speaker, but not the talks that are out of the running, so that no one is booked on too many talks and a rejected or withdrawn talk doesn't block a new one.

## What changes

This changes the limit of 3 talks per speaker from [Talk submissions](../talks/spec.md), and replaces the rule in [Co-speakers](../co-speakers/spec.md) that co-speaking doesn't count.

- A speaker's 3 talks now include the talks they co-speak on, not only their own.
- Talks that are rejected or withdrawn ([Acceptance state machine](../acceptance/spec.md)) no longer count, for the main speaker or the co-speakers. Taking someone off a talk as co-speaker frees their place too. A freed place can be used straight away.
- So a speaker at the limit can't submit a new talk, and can't be added as a co-speaker to a talk that's still in the running. Being added to a rejected or withdrawn talk is fine, since it doesn't count.
- Popular speakers get submissions and co-speaker invitations piling in together. The limit must still hold.
- Nothing that already exists is removed or changed. Speakers who are over the new limit keep everything they have, but get nothing new until they're below it.
- A speaker's talk list still shows only the talks where they are the main speaker.

## API

No new endpoints and no new fields.
