# Scheduling (nobody speaks in two rooms at once). The programme is one global grid: this file owns cells R2/S2, R3/S2, R2/S3, R3/S3 and clears them first
# and last, so parallel Hurl jobs don't collide. Local only: check.sh skips scheduling-*.hurl for other base URLs,
# so we never write to the production programme the harness uses.

DELETE {{base_url}}/programme/R2/S2
HTTP 204

DELETE {{base_url}}/programme/R3/S2
HTTP 204

DELETE {{base_url}}/programme/R2/S3
HTTP 204

DELETE {{base_url}}/programme/R3/S3
HTTP 204

# one main speaker, two talks, one slot
POST {{base_url}}/speakers
{ "name": "a", "email": "a-{{newUuid}}@example.com" }
HTTP 201
[Captures]
a_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "a", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{a_sp}} }
HTTP 201
[Captures]
a: jsonpath "$.id"

PUT {{base_url}}/talks/{{a}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{a}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{a}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/talks
{ "title": "b", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{a_sp}} }
HTTP 201
[Captures]
b: jsonpath "$.id"

PUT {{base_url}}/talks/{{b}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{b}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{b}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R2/S2
{ "talkId": {{a}} }
HTTP 200

PUT {{base_url}}/programme/R3/S2
{ "talkId": {{b}} }
HTTP 409

# a different slot is fine
PUT {{base_url}}/programme/R3/S3
{ "talkId": {{b}} }
HTTP 200

DELETE {{base_url}}/programme/R3/S3
HTTP 204

# a co-speaker of one talk is the main speaker of the other
POST {{base_url}}/speakers
{ "name": "c", "email": "c-{{newUuid}}@example.com" }
HTTP 201
[Captures]
c_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "c", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{c_sp}} }
HTTP 201
[Captures]
c: jsonpath "$.id"

PUT {{base_url}}/talks/{{c}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{c}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{c}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/talks/{{c}}/co-speakers/{{a_sp}}
HTTP 200

PUT {{base_url}}/programme/R3/S2
{ "talkId": {{c}} }
HTTP 409

# co-speaker against co-speaker
POST {{base_url}}/speakers
{ "name": "shared", "email": "shared-{{newUuid}}@example.com" }
HTTP 201
[Captures]
shared_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "shared", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{shared_sp}} }
HTTP 201
[Captures]
shared: jsonpath "$.id"

PUT {{base_url}}/talks/{{shared}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{shared}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{shared}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/speakers
{ "name": "d", "email": "d-{{newUuid}}@example.com" }
HTTP 201
[Captures]
d_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "d", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{d_sp}} }
HTTP 201
[Captures]
d: jsonpath "$.id"

PUT {{base_url}}/talks/{{d}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{d}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{d}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/speakers
{ "name": "e", "email": "e-{{newUuid}}@example.com" }
HTTP 201
[Captures]
e_sp: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "e", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{e_sp}} }
HTTP 201
[Captures]
e: jsonpath "$.id"

PUT {{base_url}}/talks/{{e}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{e}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{e}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/talks/{{d}}/co-speakers/{{shared_sp}}
HTTP 200

PUT {{base_url}}/talks/{{e}}/co-speakers/{{shared_sp}}
HTTP 200

PUT {{base_url}}/programme/R2/S3
{ "talkId": {{d}} }
HTTP 200

PUT {{base_url}}/programme/R3/S3
{ "talkId": {{e}} }
HTTP 409

DELETE {{base_url}}/programme/R2/S2
HTTP 204

DELETE {{base_url}}/programme/R3/S2
HTTP 204

DELETE {{base_url}}/programme/R2/S3
HTTP 204

DELETE {{base_url}}/programme/R3/S3
HTTP 204

