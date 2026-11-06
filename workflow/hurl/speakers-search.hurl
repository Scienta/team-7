# Speakers: the admin search (GET /speakers/search?q=) treats its term as text. Not in a spec; it was a SQL
# injection (`x' OR 1=1 --` listed every speaker with their email), see workflow/gap-analysis-base.md.

POST {{base_url}}/speakers
{ "name": "Hopper {{newUuid}}", "email": "hopper-{{newUuid}}@example.com" }
HTTP 201
[Captures]
tag: jsonpath "$.name" regex /^Hopper (.*)$/

POST {{base_url}}/speakers
{ "name": "100% {{tag}}", "email": "percent-{{newUuid}}@example.com" }
HTTP 201

# Case-insensitive substring match
GET {{base_url}}/speakers/search
[QueryStringParams]
q: hopper {{tag}}
HTTP 200
[Asserts]
jsonpath "$" count == 1
jsonpath "$[0].name" == "Hopper {{tag}}"

# SQL in the term is just text: no error, matches nobody
GET {{base_url}}/speakers/search
[QueryStringParams]
q: x' OR 1=1 --
HTTP 200
[Asserts]
jsonpath "$" count == 0

GET {{base_url}}/speakers/search
[QueryStringParams]
q: '
HTTP 200

# LIKE wildcards match literally
GET {{base_url}}/speakers/search
[QueryStringParams]
q: % {{tag}}
HTTP 200
[Asserts]
jsonpath "$" count == 1
jsonpath "$[0].name" == "100% {{tag}}"

GET {{base_url}}/speakers/search
[QueryStringParams]
q: _opper {{tag}}
HTTP 200
[Asserts]
jsonpath "$" count == 0
