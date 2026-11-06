# Workshop registration: only ACCEPTED workshops take registrations (409 otherwise, also for a talk that isn't a
# workshop); a talk that doesn't exist is 404, and GET on a talk that isn't a workshop is 404.

POST {{base_url}}/speakers
{ "name": "Submitted", "email": "submitted-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
submitted_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Submitted workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{submitted_speaker}} }
HTTP 201
[Captures]
submitted: jsonpath "$.id"

POST {{base_url}}/workshops/{{submitted}}/registrations
{ "name": "A", "email": "a-{{newUuid}}@example.com" }
HTTP 409

POST {{base_url}}/speakers
{ "name": "Waitlisted", "email": "waitlisted-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
waitlisted_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Waitlisted workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{waitlisted_speaker}} }
HTTP 201
[Captures]
waitlisted: jsonpath "$.id"

PUT {{base_url}}/talks/{{waitlisted}}/status
{ "status": "WAITLISTED" }
HTTP 200

POST {{base_url}}/workshops/{{waitlisted}}/registrations
{ "name": "A", "email": "a-{{newUuid}}@example.com" }
HTTP 409

POST {{base_url}}/speakers
{ "name": "Rejected", "email": "rejected-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
rejected_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Rejected workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{rejected_speaker}} }
HTTP 201
[Captures]
rejected: jsonpath "$.id"

PUT {{base_url}}/talks/{{rejected}}/status
{ "status": "REJECTED" }
HTTP 200

POST {{base_url}}/workshops/{{rejected}}/registrations
{ "name": "A", "email": "a-{{newUuid}}@example.com" }
HTTP 409

POST {{base_url}}/speakers
{ "name": "Talk", "email": "talk-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
talk_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk workshop", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{talk_speaker}} }
HTTP 201
[Captures]
talk: jsonpath "$.id"

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{talk}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{talk}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/workshops/{{talk}}/registrations
{ "name": "A", "email": "a-{{newUuid}}@example.com" }
HTTP 409

GET {{base_url}}/workshops/{{talk}}/registrations
HTTP 404

POST {{base_url}}/workshops/999999999/registrations
{ "name": "A", "email": "a-{{newUuid}}@example.com" }
HTTP 404

GET {{base_url}}/workshops/999999999/registrations
HTTP 404

GET {{base_url}}/workshops/{{submitted}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 0
