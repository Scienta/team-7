# Workshop registration: name and email are required (400), checked before the workshop's state.

POST {{base_url}}/speakers
{ "name": "Strict", "email": "ws-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ws_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Strict workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{ws_speaker}} }
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

POST {{base_url}}/workshops/{{ws}}/registrations
{ "email": "x-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "   ", "email": "x-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "X" }
HTTP 400

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "X", "email": "not-an-email" }
HTTP 400

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "X", "email": 42 }
HTTP 400

POST {{base_url}}/workshops/{{ws}}/registrations
{}
HTTP 400

POST {{base_url}}/workshops/{{ws}}/registrations
HTTP 400

GET {{base_url}}/workshops/{{ws}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 0
