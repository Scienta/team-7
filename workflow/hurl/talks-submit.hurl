# Talks: formats decide the duration, SUBMITTED, exact timestamp formats, read back, speaker's list.
# Spec: workflow/specs/talks.md

POST {{base_url}}/speakers
{
  "name": "Barbara Liskov",
  "email": "barbara-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{
  "title": "Ten minutes",
  "format": "LIGHTNING_10",
  "level": "BEGINNER",
  "speakerId": {{speaker}}
}
HTTP 201
[Captures]
t1: jsonpath "$.id"
submitted_at: jsonpath "$.submittedAt"
submitted_day: jsonpath "$.submittedAt" regex /^(\d{4}-\d{2}-\d{2})/
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.title" == "Ten minutes"
jsonpath "$.format" == "LIGHTNING_10"
jsonpath "$.level" == "BEGINNER"
jsonpath "$.speakerId" == {{speaker}}
jsonpath "$.durationMinutes" == 10
jsonpath "$.status" == "SUBMITTED"
jsonpath "$.submittedAt" matches /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+00:00$/
jsonpath "$.submittedOn" matches /^\d{4}-\d{2}-\d{2}$/
jsonpath "$.averageRating" == null
jsonpath "$.ratingCount" == 0

# submittedOn is the UTC date of submittedAt; read-back is identical
GET {{base_url}}/talks/{{t1}}
HTTP 200
[Asserts]
jsonpath "$.submittedAt" == "{{submitted_at}}"
jsonpath "$.submittedOn" == "{{submitted_day}}"
jsonpath "$.durationMinutes" == 10
jsonpath "$.ratingCount" == 0

# The client can't choose the duration
POST {{base_url}}/talks
{
  "title": "Twenty minutes",
  "format": "LIGHTNING_20",
  "level": "INTERMEDIATE",
  "speakerId": {{speaker}},
  "durationMinutes": 999
}
HTTP 201
[Captures]
t2: jsonpath "$.id"
[Asserts]
jsonpath "$.durationMinutes" == 20

POST {{base_url}}/talks
{
  "title": "Forty-five minutes",
  "format": "PRESENTATION",
  "level": "ADVANCED",
  "speakerId": {{speaker}}
}
HTTP 201
[Captures]
t3: jsonpath "$.id"
[Asserts]
jsonpath "$.durationMinutes" == 45

# The speaker's talks, oldest first, with rating fields
GET {{base_url}}/speakers/{{speaker}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 3
jsonpath "$[0].id" == {{t1}}
jsonpath "$[1].id" == {{t2}}
jsonpath "$[2].id" == {{t3}}
jsonpath "$[0].submittedAt" == "{{submitted_at}}"
jsonpath "$[2].ratingCount" == 0

# Workshop needs another speaker (limit is 3)
POST {{base_url}}/speakers
{
  "name": "Workshop Host",
  "email": "host-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker2: jsonpath "$.id"

POST {{base_url}}/talks
{
  "title": "Two hours",
  "format": "WORKSHOP",
  "level": "BEGINNER",
  "speakerId": {{speaker2}}
}
HTTP 201
[Asserts]
jsonpath "$.durationMinutes" == 120

GET {{base_url}}/talks/999999999
HTTP 404

GET {{base_url}}/speakers/999999999/talks
HTTP 404
