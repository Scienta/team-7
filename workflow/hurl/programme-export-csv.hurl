# Programme export: the CSV contract the print shop's tooling depends on (specs/programme-export.md,
# workflow/decisions/programme-export.md). Holds for any programme, so it also runs against the live service.

# text/csv even when the client asks for JSON; exactly this header line first, Unix line endings
GET {{base_url}}/programme.csv
Accept: application/json
HTTP 200
[Asserts]
header "Content-Type" startsWith "text/csv"
body startsWith "slot,start,end,room,talkId,title,format,speakers\n"
body not contains "\r\n"
body endsWith "\n"
body not endsWith "\n\n"

# the JSON view: a list, rows in programme order
GET {{base_url}}/programme
HTTP 200
[Asserts]
header "Content-Type" startsWith "application/json"
jsonpath "$" isCollection
