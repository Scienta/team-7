# Talks: full lists stay fast as the harness keeps adding data. They used to run one ratings query per talk
# (~1.5 s deployed at 584 talks, growing); now one query. Budget 1 s each, measured over the network.
# The first request warms up a scaled-to-zero instance so its cold start doesn't count.

GET {{base_url}}/health
HTTP 200

GET {{base_url}}/talks
HTTP 200
[Asserts]
duration < 1000

GET {{base_url}}/talks?sort=rating
HTTP 200
[Asserts]
duration < 1000

GET {{base_url}}/admin/stats
HTTP 200
[Asserts]
duration < 1000
