# Ratings: rate, re-rate replaces, half-up average, shown wherever a talk is, ratedAtDisplay format.
# Spec: workflow/specs/ratings.md

POST {{base_url}}/speakers
{
  "name": "Rated",
  "email": "rated-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Rate me", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
talk: jsonpath "$.id"

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 1 }
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{talk}}
jsonpath "$.reviewerId" == 1
jsonpath "$.score" == 1
# M/d/yy, h:mm AM: no leading zeros, 12-hour clock, one ordinary space before AM/PM
jsonpath "$.ratedAtDisplay" matches /^(1[0-2]|[1-9])\/([1-9]|[12]\d|3[01])\/\d{2}, (1[0-2]|[1-9]):[0-5]\d (AM|PM)$/

# Re-rating replaces the score
PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 4 }
HTTP 200
[Asserts]
jsonpath "$.score" == 4

GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.ratingCount" == 1
jsonpath "$.averageRating" == 4.0

PUT {{base_url}}/talks/{{talk}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{talk}}/ratings/3
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{talk}}/ratings/4
{ "score": 5 }
HTTP 200

# 4, 4, 4, 5 = 4.25 -> 4.3, everywhere a talk is shown
GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.ratingCount" == 4
jsonpath "$.averageRating" == 4.3

GET {{base_url}}/speakers/{{speaker}}/talks
HTTP 200
[Asserts]
jsonpath "$[0].ratingCount" == 4
jsonpath "$[0].averageRating" == 4.3

GET {{base_url}}/talks?speakerId={{speaker}}
HTTP 200
[Asserts]
jsonpath "$" count == 1
jsonpath "$[0].ratingCount" == 4
jsonpath "$[0].averageRating" == 4.3

GET {{base_url}}/talks?speakerId={{speaker}}&sort=rating
HTTP 200
[Asserts]
jsonpath "$[0].averageRating" == 4.3
