# Workshop registration: 20 seats, never more (sequential; the rush is in workflow/bin/race.sh).

POST {{base_url}}/speakers
{ "name": "Full", "email": "ws-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ws_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Full workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{ws_speaker}} }
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
{ "name": "Seat 1", "email": "seat1-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 2", "email": "seat2-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 3", "email": "seat3-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 4", "email": "seat4-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 5", "email": "seat5-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 6", "email": "seat6-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 7", "email": "seat7-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 8", "email": "seat8-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 9", "email": "seat9-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 10", "email": "seat10-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 11", "email": "seat11-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 12", "email": "seat12-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 13", "email": "seat13-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 14", "email": "seat14-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 15", "email": "seat15-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 16", "email": "seat16-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 17", "email": "seat17-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 18", "email": "seat18-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 19", "email": "seat19-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 20", "email": "seat20-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{ws}}/registrations
{ "name": "Seat 21", "email": "seat21-{{newUuid}}@example.com" }
HTTP 409

GET {{base_url}}/workshops/{{ws}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 20
jsonpath "$[0].name" == "Seat 1"
jsonpath "$[19].name" == "Seat 20"
