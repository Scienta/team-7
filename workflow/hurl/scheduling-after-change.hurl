# Scheduling (rules are checked when a talk is put on the programme, not afterwards). The programme is one global grid: this file owns cells R1/S4 and clears them first
# and last, so parallel Hurl jobs don't collide. Local only: check.sh skips scheduling-*.hurl for other base URLs,
# so we never write to the production programme the harness uses.

DELETE {{base_url}}/programme/R1/S4
HTTP 204

POST {{base_url}}/speakers
{ "name": "kept", "email": "kept-{{newUuid}}@example.com" }
HTTP 201
[Captures]
kept_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "kept", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{kept_sp}} }
HTTP 201
[Captures]
kept: jsonpath "$.id"

PUT {{base_url}}/talks/{{kept}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{kept}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{kept}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/speakers
{ "name": "other", "email": "other-{{newUuid}}@example.com" }
HTTP 201
[Captures]
other_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "other", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{other_sp}} }
HTTP 201
[Captures]
other: jsonpath "$.id"

PUT {{base_url}}/talks/{{other}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{other}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{other}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R1/S4
{ "talkId": {{kept}} }
HTTP 200

# a new co-speaker and a withdrawal afterwards: the programme stays as it is
PUT {{base_url}}/talks/{{kept}}/co-speakers/{{other_sp}}
HTTP 200

POST {{base_url}}/talks/{{kept}}/withdraw
{ "speakerId": {{kept_sp}} }
HTTP 200

GET {{base_url}}/programme/R1/S4
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{kept}}

DELETE {{base_url}}/programme/R1/S4
HTTP 204

