# Ratings: only whole scores 1-5, only positive reviewer numbers, only existing talks.

POST {{base_url}}/speakers
{
  "name": "Strict",
  "email": "strict-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Strictly rated", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
talk: jsonpath "$.id"

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 0 }
HTTP 400

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 6 }
HTTP 400

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 4.5 }
HTTP 400

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": "4" }
HTTP 400

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": null }
HTTP 400

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ }
HTTP 400

PUT {{base_url}}/talks/{{talk}}/ratings/0
{ "score": 3 }
HTTP 400

PUT {{base_url}}/talks/{{talk}}/ratings/-1
{ "score": 3 }
HTTP 400

PUT {{base_url}}/talks/{{talk}}/ratings/abc
{ "score": 3 }
HTTP 400

PUT {{base_url}}/talks/{{talk}}/ratings/1.5
{ "score": 3 }
HTTP *
[Asserts]
status >= 400
status < 500

# None of the above counted
GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.ratingCount" == 0
jsonpath "$.averageRating" == null

PUT {{base_url}}/talks/999999999/ratings/1
{ "score": 3 }
HTTP 404
