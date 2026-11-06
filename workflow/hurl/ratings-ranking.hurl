# Ratings: ranking by the shown average (2 decimals since ratings-cr), then rating count, then oldest; unrated last.
# One speaker's talks only, so other data in the database doesn't matter.
#   t1: 4, 4, 4, 5 = 4.25, 4 ratings
#   t2: unrated
#   t3: 4, 5, 4 = 4.333 -> 4.33, 3 ratings  (with 1 decimal both showed 4.3 and t1 won on count; now t3 is ahead)
# Expected ranking: t3, t1, t2.

POST {{base_url}}/speakers
{
  "name": "Ranked",
  "email": "ranked-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "t1", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
t1: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "t2", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
t2: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "t3", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
t3: jsonpath "$.id"

PUT {{base_url}}/talks/{{t1}}/ratings/1
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{t1}}/ratings/2
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{t1}}/ratings/3
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{t1}}/ratings/4
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{t3}}/ratings/1
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{t3}}/ratings/2
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{t3}}/ratings/3
{ "score": 4 }
HTTP 200

GET {{base_url}}/talks?speakerId={{speaker}}&sort=rating
HTTP 200
[Asserts]
jsonpath "$" count == 3
jsonpath "$[0].id" == {{t3}}
jsonpath "$[1].id" == {{t1}}
jsonpath "$[2].id" == {{t2}}
jsonpath "$[0].averageRating" == 4.33
jsonpath "$[1].averageRating" == 4.25
jsonpath "$[2].averageRating" == null

# Unsorted: oldest first
GET {{base_url}}/talks?speakerId={{speaker}}
HTTP 200
[Asserts]
jsonpath "$[0].id" == {{t1}}
jsonpath "$[1].id" == {{t2}}
jsonpath "$[2].id" == {{t3}}

# The whole list ranks too
GET {{base_url}}/talks?sort=rating
HTTP 200
[Asserts]
jsonpath "$" count >= 3
