# Submission cap change request (specs/submission-cap-cr.md, workflow/decisions/submission-cap-cr.md): at the limit, being added to a rejected or
# withdrawn talk is fine (it doesn't count), and re-adding an existing co-speaking place changes nothing.

POST {{base_url}}/speakers
{ "name": "z", "email": "z-{{newUuid}}@example.com" }
HTTP 201
[Captures]
z: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "host1", "email": "host1-{{newUuid}}@example.com" }
HTTP 201
[Captures]
host1: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "h1", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{host1}} }
HTTP 201
[Captures]
h1: jsonpath "$.id"

PUT {{base_url}}/talks/{{h1}}/co-speakers/{{z}}
HTTP 200

POST {{base_url}}/talks
{ "title": "z1", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{z}} }
HTTP 201
[Captures]
z1: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "z2", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{z}} }
HTTP 201
[Captures]
z2: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "z3", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{z}} }
HTTP 409

POST {{base_url}}/speakers
{ "name": "host2", "email": "host2-{{newUuid}}@example.com" }
HTTP 201
[Captures]
host2: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "rejected", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{host2}} }
HTTP 201
[Captures]
rejected: jsonpath "$.id"

PUT {{base_url}}/talks/{{rejected}}/status
{ "status": "REJECTED" }
HTTP 200

PUT {{base_url}}/talks/{{rejected}}/co-speakers/{{z}}
HTTP 200

POST {{base_url}}/speakers
{ "name": "host3", "email": "host3-{{newUuid}}@example.com" }
HTTP 201
[Captures]
host3: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "withdrawn", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{host3}} }
HTTP 201
[Captures]
withdrawn: jsonpath "$.id"

POST {{base_url}}/talks/{{withdrawn}}/withdraw
{ "speakerId": {{host3}} }
HTTP 200

PUT {{base_url}}/talks/{{withdrawn}}/co-speakers/{{z}}
HTTP 200

# already a co-speaker of h1: 200, nothing changes
PUT {{base_url}}/talks/{{h1}}/co-speakers/{{z}}
HTTP 200

GET {{base_url}}/talks/{{h1}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1

# still at the limit for anything in the running
POST {{base_url}}/talks
{ "title": "z4", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{z}} }
HTTP 409

