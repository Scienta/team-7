# Submission cap change request (specs/submission-cap-cr.md, workflow/decisions/submission-cap-cr.md): co-speaking counts towards the 3 talks,
# for submissions and for being added as co-speaker; the own list still shows main-speaker talks only.

POST {{base_url}}/speakers
{ "name": "x", "email": "x-{{newUuid}}@example.com" }
HTTP 201
[Captures]
x: jsonpath "$.id"

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

PUT {{base_url}}/talks/{{h1}}/co-speakers/{{x}}
HTTP 200

POST {{base_url}}/speakers
{ "name": "host2", "email": "host2-{{newUuid}}@example.com" }
HTTP 201
[Captures]
host2: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "h2", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{host2}} }
HTTP 201
[Captures]
h2: jsonpath "$.id"

PUT {{base_url}}/talks/{{h2}}/co-speakers/{{x}}
HTTP 200

# 2 co-speaking places + 1 own talk = 3
POST {{base_url}}/talks
{ "title": "own", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{x}} }
HTTP 201
[Captures]
own: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "over", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{x}} }
HTTP 409

POST {{base_url}}/speakers
{ "name": "host3", "email": "host3-{{newUuid}}@example.com" }
HTTP 201
[Captures]
host3: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "h3", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{host3}} }
HTTP 201
[Captures]
h3: jsonpath "$.id"

# and no third co-speaking place either
PUT {{base_url}}/talks/{{h3}}/co-speakers/{{x}}
HTTP 409

GET {{base_url}}/speakers/{{x}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 1
jsonpath "$[0].id" == {{own}}
