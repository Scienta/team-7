# Speakers: "needs a real email". Gap G1 in workflow/gap-analysis-base.md.

POST {{base_url}}/speakers
{
  "name": "No Email",
  "email": "not-an-email-{{newUuid}}"
}
HTTP 400

POST {{base_url}}/speakers
{
  "name": "No Local Part",
  "email": "@{{newUuid}}.example.com"
}
HTTP 400

POST {{base_url}}/speakers
{
  "name": "No Domain",
  "email": "{{newUuid}}@"
}
HTTP 400
