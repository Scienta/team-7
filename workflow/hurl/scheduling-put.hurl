# Scheduling (put, look up, take out, move). The programme is one global grid: this file owns cells R1/S1, R2/S5 and clears them first
# and last, so parallel Hurl jobs don't collide. Local only: check.sh skips scheduling-*.hurl for other base URLs,
# so we never write to the production programme the harness uses.

DELETE {{base_url}}/programme/R1/S1
HTTP 204

DELETE {{base_url}}/programme/R2/S5
HTTP 204

POST {{base_url}}/speakers
{ "name": "t", "email": "t-{{newUuid}}@example.com" }
HTTP 201
[Captures]
t_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "t", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{t_sp}} }
HTTP 201
[Captures]
t: jsonpath "$.id"

PUT {{base_url}}/talks/{{t}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{t}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{t}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R1/S1
{ "talkId": {{t}} }
HTTP 200
[Asserts]
jsonpath "$.room" == "R1"
jsonpath "$.slot" == "S1"
jsonpath "$.start" == "09:00"
jsonpath "$.end" == "10:00"
jsonpath "$.talkId" == {{t}}

GET {{base_url}}/programme/R1/S1
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{t}}

# to move a talk, take it out first
PUT {{base_url}}/programme/R2/S5
{ "talkId": {{t}} }
HTTP 409

DELETE {{base_url}}/programme/R1/S1
HTTP 204

GET {{base_url}}/programme/R1/S1
HTTP 200
[Asserts]
jsonpath "$.talkId" == null

PUT {{base_url}}/programme/R2/S5
{ "talkId": {{t}} }
HTTP 200
[Asserts]
jsonpath "$.start" == "15:45"

# taking out an empty cell is fine too
DELETE {{base_url}}/programme/R1/S1
HTTP 204

DELETE {{base_url}}/programme/R1/S1
HTTP 204

DELETE {{base_url}}/programme/R2/S5
HTTP 204

