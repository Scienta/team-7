# Scheduling (venue: fixed rooms and slots). The programme is one global grid: this file owns cells R3/S1 and clears them first
# and last, so parallel Hurl jobs don't collide. Local only: check.sh skips scheduling-*.hurl for other base URLs,
# so we never write to the production programme the harness uses.

DELETE {{base_url}}/programme/R3/S1
HTTP *
[Asserts]
status >= 204
status <= 404

# an empty cell has nothing to show (harness scheduling/check-2..5)
GET {{base_url}}/programme/R3/S1
HTTP 404

GET {{base_url}}/programme/R4/S1
HTTP 404

DELETE {{base_url}}/programme/R4/S1
HTTP 404

PUT {{base_url}}/programme/R4/S1
{ "talkId": 1 }
HTTP 404

GET {{base_url}}/programme/R1/S6
HTTP 404

DELETE {{base_url}}/programme/R1/S6
HTTP 404

PUT {{base_url}}/programme/R1/S6
{ "talkId": 1 }
HTTP 404

GET {{base_url}}/programme/r1/S1
HTTP 404

DELETE {{base_url}}/programme/r1/S1
HTTP 404

PUT {{base_url}}/programme/r1/S1
{ "talkId": 1 }
HTTP 404

GET {{base_url}}/programme/R1/s1
HTTP 404

DELETE {{base_url}}/programme/R1/s1
HTTP 404

PUT {{base_url}}/programme/R1/s1
{ "talkId": 1 }
HTTP 404

GET {{base_url}}/programme/R0/S0
HTTP 404

DELETE {{base_url}}/programme/R0/S0
HTTP 404

PUT {{base_url}}/programme/R0/S0
{ "talkId": 1 }
HTTP 404
