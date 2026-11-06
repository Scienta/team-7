# Talks: at most 3 per speaker (sequential; the parallel case is in workflow/bin/race.sh).

POST {{base_url}}/speakers
{
  "name": "Prolific",
  "email": "prolific-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "One", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201

POST {{base_url}}/talks
{ "title": "Two", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201

POST {{base_url}}/talks
{ "title": "Three", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201

# The fourth is refused (409 today)
POST {{base_url}}/talks
{ "title": "Four", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP *
[Asserts]
status >= 400
status < 500

GET {{base_url}}/speakers/{{speaker}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 3
