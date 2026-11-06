# Ratings: a reviewer is "a positive whole number"; big ones are reviewers too.
# Gap G3 in workflow/gap-analysis-base.md (was 500 "integer out of range").
# Team decision: accept any reviewer up to 2^63-1 (reviewer_id is BIGINT); beyond that, 400.

POST {{base_url}}/speakers
{
  "name": "Big Reviewer",
  "email": "big-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Big numbers", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
talk: jsonpath "$.id"

PUT {{base_url}}/talks/{{talk}}/ratings/2147483648
{ "score": 3 }
HTTP 200
[Asserts]
jsonpath "$.reviewerId" == 2147483648

PUT {{base_url}}/talks/{{talk}}/ratings/99999999999
{ "score": 5 }
HTTP 200
[Asserts]
jsonpath "$.reviewerId" == 99999999999

GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.ratingCount" == 2
jsonpath "$.averageRating" == 4.0

PUT {{base_url}}/talks/{{talk}}/ratings/99999999999999999999999
{ "score": 3 }
HTTP 400
