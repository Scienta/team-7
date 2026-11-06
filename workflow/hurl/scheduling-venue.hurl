# Scheduling (venue: fixed rooms and slots, times). The programme is one global grid: this file owns cells R3/S1 and clears them first
# and last, so parallel Hurl jobs don't collide. Local only: check.sh skips scheduling-*.hurl for other base URLs,
# so we never write to the production programme the harness uses.

DELETE {{base_url}}/programme/R3/S1
HTTP 204

GET {{base_url}}/programme/R3/S1
HTTP 200
[Asserts]
jsonpath "$.room" == "R3"
jsonpath "$.slot" == "S1"
jsonpath "$.start" == "09:00"
jsonpath "$.end" == "10:00"
jsonpath "$.talkId" == null

# times only: another file may own this cell
GET {{base_url}}/programme/R1/S2
HTTP 200
[Asserts]
jsonpath "$.start" == "10:15"
jsonpath "$.end" == "11:15"

# times only: another file may own this cell
GET {{base_url}}/programme/R2/S3
HTTP 200
[Asserts]
jsonpath "$.start" == "11:30"
jsonpath "$.end" == "12:30"

# times only: another file may own this cell
GET {{base_url}}/programme/R3/S4
HTTP 200
[Asserts]
jsonpath "$.start" == "13:30"
jsonpath "$.end" == "15:30"

# times only: another file may own this cell
GET {{base_url}}/programme/R1/S5
HTTP 200
[Asserts]
jsonpath "$.start" == "15:45"
jsonpath "$.end" == "16:45"

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

