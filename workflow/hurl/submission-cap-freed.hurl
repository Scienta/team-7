# Submission cap change request (specs/submission-cap-cr.md, workflow/decisions/submission-cap-cr.md): rejected and withdrawn talks don't count,
# and taking someone off as co-speaker frees their place, straight away.

POST {{base_url}}/speakers
{ "name": "y", "email": "y-{{newUuid}}@example.com" }
HTTP 201
[Captures]
y: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "a", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{y}} }
HTTP 201
[Captures]
a: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "b", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{y}} }
HTTP 201
[Captures]
b: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "c", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{y}} }
HTTP 201
[Captures]
c: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "d", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{y}} }
HTTP 409

# a rejection frees a place
PUT {{base_url}}/talks/{{a}}/status
{ "status": "REJECTED" }
HTTP 200

POST {{base_url}}/talks
{ "title": "e", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{y}} }
HTTP 201
[Captures]
e: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "f", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{y}} }
HTTP 409

# a withdrawal frees a place
POST {{base_url}}/talks/{{b}}/withdraw
{ "speakerId": {{y}} }
HTTP 200

POST {{base_url}}/speakers
{ "name": "host", "email": "host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
host: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "h", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{host}} }
HTTP 201
[Captures]
h: jsonpath "$.id"

PUT {{base_url}}/talks/{{h}}/co-speakers/{{y}}
HTTP 200

# full again (c, e, co-speaking on h); taking y off h frees the place
POST {{base_url}}/talks
{ "title": "g", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{y}} }
HTTP 409

DELETE {{base_url}}/talks/{{h}}/co-speakers/{{y}}
HTTP 200

POST {{base_url}}/talks
{ "title": "i", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{y}} }
HTTP 201
[Captures]
i: jsonpath "$.id"

