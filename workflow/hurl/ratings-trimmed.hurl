# Ratings change request (specs/ratings-cr.md): from 5 ratings on, the average leaves out exactly one highest and
# one lowest score; 2 decimals, ties at the third decimal to the even neighbour; ratingCount counts everyone.

POST {{base_url}}/speakers
{ "name": "Trimmed", "email": "trimmed-{{newUuid}}@example.com" }
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "four", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
four: jsonpath "$.id"

# a second speaker: a speaker submits at most 3 talks
POST {{base_url}}/speakers
{ "name": "Trimmed too", "email": "trimmed2-{{newUuid}}@example.com" }
HTTP 201
[Captures]
speaker2: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "five", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker2}} }
HTTP 201
[Captures]
five: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "tie_down", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
tie_down: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "tie_up", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
tie_up: jsonpath "$.id"

# four: 4, 4, 4, 5
PUT {{base_url}}/talks/{{four}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{four}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{four}}/ratings/3
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{four}}/ratings/4
{ "score": 5 }
HTTP 200

# five: 1, 3, 3, 3, 5
PUT {{base_url}}/talks/{{five}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{five}}/ratings/2
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{five}}/ratings/3
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{five}}/ratings/4
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{five}}/ratings/5
{ "score": 5 }
HTTP 200

# tie_down: 1, 5, 5, 5, 3, 3, 3, 2, 2, 2
PUT {{base_url}}/talks/{{tie_down}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{tie_down}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{tie_down}}/ratings/3
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{tie_down}}/ratings/4
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{tie_down}}/ratings/5
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{tie_down}}/ratings/6
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{tie_down}}/ratings/7
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{tie_down}}/ratings/8
{ "score": 2 }
HTTP 200

PUT {{base_url}}/talks/{{tie_down}}/ratings/9
{ "score": 2 }
HTTP 200

PUT {{base_url}}/talks/{{tie_down}}/ratings/10
{ "score": 2 }
HTTP 200

# tie_up: 1, 5, 5, 5, 4, 3, 3, 3, 2, 2
PUT {{base_url}}/talks/{{tie_up}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{tie_up}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{tie_up}}/ratings/3
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{tie_up}}/ratings/4
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{tie_up}}/ratings/5
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{tie_up}}/ratings/6
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{tie_up}}/ratings/7
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{tie_up}}/ratings/8
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{tie_up}}/ratings/9
{ "score": 2 }
HTTP 200

PUT {{base_url}}/talks/{{tie_up}}/ratings/10
{ "score": 2 }
HTTP 200

# 4 ratings: all count
GET {{base_url}}/talks/{{four}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.25
jsonpath "$.ratingCount" == 4

# 5 ratings: one 1 and one 5 out, (3+3+3)/3
GET {{base_url}}/talks/{{five}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 3.0
jsonpath "$.ratingCount" == 5

# one 1 and one 5 out: 25/8 = 3.125 -> 3.12
GET {{base_url}}/talks/{{tie_down}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 3.12
jsonpath "$.ratingCount" == 10

# one 1 and one 5 out: 27/8 = 3.375 -> 3.38
GET {{base_url}}/talks/{{tie_up}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 3.38
jsonpath "$.ratingCount" == 10

# The ranking uses the new average: four 4.25, tie_up 3.38, tie_down 3.12
GET {{base_url}}/talks?speakerId={{speaker}}&sort=rating
HTTP 200
[Asserts]
jsonpath "$" count == 3
jsonpath "$[0].id" == {{four}}
jsonpath "$[1].id" == {{tie_up}}
jsonpath "$[2].id" == {{tie_down}}
