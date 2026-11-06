# Workshop registration: register, email kept as typed, one per email (any case), same person on several
# workshops, list in registration order. Rules: specs/workshop-registration.md, workflow/decisions/workshop-registration.md

POST {{base_url}}/speakers
{ "name": "Ada", "email": "ws-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ws_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Ada workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{ws_speaker}} }
HTTP 201
[Captures]
ws: jsonpath "$.id"

PUT {{base_url}}/talks/{{ws}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{ws}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{ws}}/status
{ "status": "ACCEPTED" }
HTTP 200
POST {{base_url}}/speakers
{ "name": "Grace", "email": "other-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
other_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Grace workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{other_speaker}} }
HTTP 201
[Captures]
other: jsonpath "$.id"

PUT {{base_url}}/talks/{{other}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{other}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{other}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Ada Lovelace", "email": "Ada-{{newUuid}}@Example.com" }
HTTP 201
[Captures]
first_email: jsonpath "$.email"
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.workshopId" == {{ws}}
jsonpath "$.name" == "Ada Lovelace"
jsonpath "$.email" matches /^Ada-.*@Example\.com$/

# the same person in other letter case: one registration per email per workshop
POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Ada again", "email": "{{first_email}}" }
HTTP 409

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Charles Babbage", "email": "charles-{{newUuid}}@example.com" }
HTTP 201

# the same person can register for another workshop
POST {{base_url}}/workshops/{{other}}/registrations
{ "name": "Ada Lovelace", "email": "{{first_email}}" }
HTTP 201

GET {{base_url}}/workshops/{{ws}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 2
jsonpath "$[0].name" == "Ada Lovelace"
jsonpath "$[0].email" == "{{first_email}}"
jsonpath "$[1].name" == "Charles Babbage"
jsonpath "$[0].workshopId" == {{ws}}
