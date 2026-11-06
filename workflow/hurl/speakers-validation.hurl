# Speakers: a real name and email are required; one email (any case) is one speaker.

POST {{base_url}}/speakers
{ "email": "noname-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/speakers
{ "name": "   ", "email": "blankname-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/speakers
{ "name": "No Email" }
HTTP 400

POST {{base_url}}/speakers
{ "name": "Blank Email", "email": "  " }
HTTP 400

POST {{base_url}}/speakers
{ "name": "Ada Lovelace", "email": "ada-{{newUuid}}@example.com" }
HTTP 201
[Captures]
email: jsonpath "$.email"

POST {{base_url}}/speakers
{ "name": "Ada Again", "email": "{{email}}" }
HTTP 409

# Same email, other case: same person (409 today)
POST {{base_url}}/speakers
{ "name": "Grace", "email": "grace-{{newUuid}}@example.com" }
HTTP 201
[Captures]
local: jsonpath "$.email" regex /^grace-(.*)@example\.com$/

POST {{base_url}}/speakers
{ "name": "GRACE", "email": "GRACE-{{local}}@EXAMPLE.COM" }
HTTP 409
