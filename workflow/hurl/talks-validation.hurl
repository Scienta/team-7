# Talks: only exact formats and levels, a real title, a registered speaker.
# Status codes for invalid input are our choice (spec is silent): 400 for a malformed talk.

POST {{base_url}}/speakers
{
  "name": "Validator",
  "email": "validator-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "x", "format": "workshop", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "x", "format": "Workshop", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "x", "format": "KEYNOTE", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "x", "format": "LIGHTNING", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "x", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "x", "format": "WORKSHOP", "level": "beginner", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "x", "format": "WORKSHOP", "level": "EXPERT", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "x", "format": "WORKSHOP", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "   ", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "x", "format": "WORKSHOP", "level": "BEGINNER" }
HTTP 400

# Unknown speaker: some 4xx (400 today)
POST {{base_url}}/talks
{ "title": "x", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": 999999999 }
HTTP *
[Asserts]
status >= 400
status < 500

# None of the rejected ones were stored
GET {{base_url}}/speakers/{{speaker}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 0
