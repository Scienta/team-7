# Co-speakers: our own examples (drip feature, so none are published). Decisions: specs/co-speakers.md.
# Run: hurl --test --variable base_url=http://localhost:8080 co-speakers.hurl

# Setup: a main speaker with a talk, and three other speakers
POST {{base_url}}/speakers
{ "name": "Main Speaker", "email": "main-{{newUuid}}@example.com" }
HTTP 201
[Captures]
main: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co Speaker A", "email": "a-{{newUuid}}@example.com" }
HTTP 201
[Captures]
a: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co Speaker B", "email": "b-{{newUuid}}@example.com" }
HTTP 201
[Captures]
b: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co Speaker C", "email": "c-{{newUuid}}@example.com" }
HTTP 201
[Captures]
c: jsonpath "$.id"

# A new talk has no co-speakers
POST {{base_url}}/talks
{ "title": "Pairing on stage", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{main}} }
HTTP 201
[Captures]
talk: jsonpath "$.id"
[Asserts]
jsonpath "$.coSpeakerIds" count == 0

# Add B, then A: shown in the order they were added
PUT {{base_url}}/talks/{{talk}}/co-speakers/{{b}}
HTTP 200
[Asserts]
jsonpath "$.id" == {{talk}}
jsonpath "$.speakerId" == {{main}}
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{b}}

PUT {{base_url}}/talks/{{talk}}/co-speakers/{{a}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{b}}
jsonpath "$.coSpeakerIds[1]" == {{a}}

# Adding B again changes nothing, also when the talk is full
PUT {{base_url}}/talks/{{talk}}/co-speakers/{{b}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{b}}

# A third co-speaker is one too many
PUT {{base_url}}/talks/{{talk}}/co-speakers/{{c}}
HTTP 409

# The main speaker can't co-speak their own talk
PUT {{base_url}}/talks/{{talk}}/co-speakers/{{main}}
HTTP 400

# Unknown talk or speaker
PUT {{base_url}}/talks/2147483647/co-speakers/{{a}}
HTTP 404

PUT {{base_url}}/talks/{{talk}}/co-speakers/2147483647
HTTP 404

# Every talk shows its co-speakers
GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds[0]" == {{b}}
jsonpath "$.coSpeakerIds[1]" == {{a}}

# A co-speaker's own talk list only has talks where they're the main speaker
GET {{base_url}}/speakers/{{a}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 0

# Remove B; A stays. Removing B again is a 404 and changes nothing
DELETE {{base_url}}/talks/{{talk}}/co-speakers/{{b}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{a}}

DELETE {{base_url}}/talks/{{talk}}/co-speakers/{{b}}
HTTP 404

GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1

# Now there's room for C, after A
PUT {{base_url}}/talks/{{talk}}/co-speakers/{{c}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds[0]" == {{a}}
jsonpath "$.coSpeakerIds[1]" == {{c}}
