# Workshop registration: a workshop withdrawn after people registered keeps them on its list, and takes no more.

POST {{base_url}}/speakers
{ "name": "Withdrawn", "email": "ws-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ws_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Withdrawn workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{ws_speaker}} }
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
{ "name": "Early", "email": "early-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/talks/{{ws}}/withdraw
{ "speakerId": {{ws_speaker}} }
HTTP 200

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Late", "email": "late-{{newUuid}}@example.com" }
HTTP 409

GET {{base_url}}/workshops/{{ws}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 1
jsonpath "$[0].name" == "Early"
