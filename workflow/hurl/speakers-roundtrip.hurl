# Speakers: read back exactly as registered, optional bio, trailing slashes, unknown ids.
# Spec: workflow/specs/speakers.md

POST {{base_url}}/speakers/
{
  "name": "Grace Hopper",
  "email": "Grace-{{newUuid}}@Example.COM",
  "bio": "Ships, compilers & bugs — ünïcode æøå"
}
HTTP 201
[Captures]
id: jsonpath "$.id"
email: jsonpath "$.email"
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.name" == "Grace Hopper"
jsonpath "$.email" matches /^Grace-.*@Example\.COM$/
jsonpath "$.bio" == "Ships, compilers & bugs — ünïcode æøå"

GET {{base_url}}/speakers/{{id}}
HTTP 200
[Asserts]
jsonpath "$.id" == {{id}}
jsonpath "$.name" == "Grace Hopper"
jsonpath "$.email" == "{{email}}"
jsonpath "$.bio" == "Ships, compilers & bugs — ünïcode æøå"

GET {{base_url}}/speakers/{{id}}/
HTTP 200
[Asserts]
jsonpath "$.id" == {{id}}
jsonpath "$.email" == "{{email}}"

# No bio: optional, comes back null
POST {{base_url}}/speakers
{
  "name": "Edsger Dijkstra",
  "email": "edsger-{{newUuid}}@example.com"
}
HTTP 201
[Asserts]
jsonpath "$.bio" == null

GET {{base_url}}/speakers/999999999
HTTP 404

GET {{base_url}}/speakers/999999999/
HTTP 404

# Not a number: client error, not a crash
GET {{base_url}}/speakers/abc
HTTP *
[Asserts]
status >= 400
status < 500
