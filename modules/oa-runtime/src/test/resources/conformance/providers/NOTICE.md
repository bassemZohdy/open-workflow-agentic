# Controlled CTK provider fixtures

petstore-swagger.json is the UNMODIFIED Swagger Petstore 2.0 document retrieved
from https://petstore.swagger.io/v2/swagger.json. The source metadata records
retrieval time, original SHA-256 and byte length. Its info.license declares
Apache 2.0; the full license is retained in LICENSE. Tests load only these pinned
bytes for the original CTK document URI, with no public-service invocation.
The real SDK parser sees the original host/basePath/operations/security/refs.
All document references are internal. Outgoing HTTP is substituted at the
per-client transport boundary with bounded owned loopback fixtures; this does
not prove public service availability, TLS or production target permission.
Fixture HTTP response bodies are authored here and retain required Petstore
response fields. The two try scenarios receive404 as intended by the pinned
feature comments; the503 catcher remains unmatched. Basic credentials are the
public conformance-test placeholders from the official feature. Actual request,
resource and event records are retained separately from workflow assertions.
