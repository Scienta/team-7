# Scheduling (one talk per cell, a talk on the programme once). The programme is one global grid: this file owns cells R1/S3, R3/S5 and clears them first
# and last, so parallel Hurl jobs don't collide. Local only: check.sh skips scheduling-*.hurl for other base URLs,
# so we never write to the production programme the harness uses.

DELETE {{base_url}}/programme/R1/S3
HTTP 204

DELETE {{base_url}}/programme/R3/S5
HTTP 204

POST {{base_url}}/speakers
{ "name": "first", "email": "first-{{newUuid}}@example.com" }
HTTP 201
[Captures]
first_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "first", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{first_sp}} }
HTTP 201
[Captures]
first: jsonpath "$.id"

PUT {{base_url}}/talks/{{first}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{first}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{first}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/speakers
{ "name": "second", "email": "second-{{newUuid}}@example.com" }
HTTP 201
[Captures]
second_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "second", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{second_sp}} }
HTTP 201
[Captures]
second: jsonpath "$.id"

PUT {{base_url}}/talks/{{second}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{second}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{second}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R1/S3
{ "talkId": {{first}} }
HTTP 200

# another talk into the occupied cell
PUT {{base_url}}/programme/R1/S3
{ "talkId": {{second}} }
HTTP 409

# the same talk again, elsewhere and in the same cell
PUT {{base_url}}/programme/R3/S5
{ "talkId": {{first}} }
HTTP 409

PUT {{base_url}}/programme/R1/S3
{ "talkId": {{first}} }
HTTP 409

GET {{base_url}}/programme/R1/S3
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{first}}

DELETE {{base_url}}/programme/R1/S3
HTTP 204

DELETE {{base_url}}/programme/R3/S5
HTTP 204

