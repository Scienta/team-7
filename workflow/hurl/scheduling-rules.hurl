# Scheduling (only accepted talks, workshops need S4, a valid body). The programme is one global grid: this file owns cells R2/S1, R2/S4 and clears them first
# and last, so parallel Hurl jobs don't collide. Local only: check.sh skips scheduling-*.hurl for other base URLs,
# so we never write to the production programme the harness uses.

DELETE {{base_url}}/programme/R2/S1
HTTP *
[Asserts]
status >= 204
status <= 404

DELETE {{base_url}}/programme/R2/S4
HTTP *
[Asserts]
status >= 204
status <= 404

POST {{base_url}}/speakers
{ "name": "submitted", "email": "submitted-{{newUuid}}@example.com" }
HTTP 201
[Captures]
submitted_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "submitted", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{submitted_sp}} }
HTTP 201
[Captures]
submitted: jsonpath "$.id"

PUT {{base_url}}/programme/R2/S1
{ "talkId": {{submitted}} }
HTTP 409

POST {{base_url}}/speakers
{ "name": "waitlisted", "email": "waitlisted-{{newUuid}}@example.com" }
HTTP 201
[Captures]
waitlisted_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "waitlisted", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{waitlisted_sp}} }
HTTP 201
[Captures]
waitlisted: jsonpath "$.id"

PUT {{base_url}}/talks/{{waitlisted}}/status
{ "status": "WAITLISTED" }
HTTP 200

PUT {{base_url}}/programme/R2/S1
{ "talkId": {{waitlisted}} }
HTTP 409

POST {{base_url}}/speakers
{ "name": "rejected", "email": "rejected-{{newUuid}}@example.com" }
HTTP 201
[Captures]
rejected_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "rejected", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{rejected_sp}} }
HTTP 201
[Captures]
rejected: jsonpath "$.id"

PUT {{base_url}}/talks/{{rejected}}/status
{ "status": "REJECTED" }
HTTP 200

PUT {{base_url}}/programme/R2/S1
{ "talkId": {{rejected}} }
HTTP 409

POST {{base_url}}/speakers
{ "name": "withdrawn", "email": "withdrawn-{{newUuid}}@example.com" }
HTTP 201
[Captures]
withdrawn_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "withdrawn", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{withdrawn_sp}} }
HTTP 201
[Captures]
withdrawn: jsonpath "$.id"

PUT {{base_url}}/talks/{{withdrawn}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{withdrawn}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{withdrawn}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/talks/{{withdrawn}}/withdraw
{ "speakerId": {{withdrawn_sp}} }
HTTP 200

PUT {{base_url}}/programme/R2/S1
{ "talkId": {{withdrawn}} }
HTTP 409

PUT {{base_url}}/programme/R2/S1
{ "talkId": 999999999 }
HTTP 400

PUT {{base_url}}/programme/R2/S1
{}
HTTP 400

PUT {{base_url}}/programme/R2/S1
{ "talkId": "1" }
HTTP 400

PUT {{base_url}}/programme/R2/S1
{ "talkId": 1.5 }
HTTP 400

PUT {{base_url}}/programme/R2/S1
HTTP 400

POST {{base_url}}/speakers
{ "name": "ws", "email": "ws-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ws_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "ws", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{ws_sp}} }
HTTP 201
[Captures]
ws: jsonpath "$.id"

PUT {{base_url}}/talks/{{ws}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{ws}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{ws}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R2/S1
{ "talkId": {{ws}} }
HTTP 409

PUT {{base_url}}/programme/R2/S4
{ "talkId": {{ws}} }
HTTP 200

DELETE {{base_url}}/programme/R2/S4
HTTP 204

POST {{base_url}}/speakers
{ "name": "lightning", "email": "lightning-{{newUuid}}@example.com" }
HTTP 201
[Captures]
lightning_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "lightning", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{lightning_sp}} }
HTTP 201
[Captures]
lightning: jsonpath "$.id"

PUT {{base_url}}/talks/{{lightning}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{lightning}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{lightning}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R2/S4
{ "talkId": {{lightning}} }
HTTP 200

DELETE {{base_url}}/programme/R2/S4
HTTP 204

PUT {{base_url}}/programme/R2/S1
{ "talkId": {{lightning}} }
HTTP 200

GET {{base_url}}/programme/R2/S1
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{lightning}}

DELETE {{base_url}}/programme/R2/S1
HTTP *
[Asserts]
status >= 204
status <= 404

DELETE {{base_url}}/programme/R2/S4
HTTP *
[Asserts]
status >= 204
status <= 404
