# http2postman

**Convert `.http` files to Postman collections in one command.**

JetBrains HTTP Client and VS Code REST Client requests (`.http` / `.rest`
files) import cleanly into Postman as a **Collection v2.1** JSON file —
requests, folders, variables, scripts, auth, and bodies included.

[![PyPI version](https://badge.fury.io/py/http2postman.svg)](https://pypi.org/project/http2postman/)
[![PyPI downloads](https://img.shields.io/pepy/dt/http2postman?label=PyPI%20downloads)](https://pypistats.org/packages/http2postman)
[![Python 3.10+](https://img.shields.io/badge/python-3.10%2B-blue.svg)](https://pypi.org/project/http2postman/)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Dependencies: none](https://img.shields.io/badge/dependencies-none-brightgreen.svg)](#installation)
[![GitHub stars](https://img.shields.io/github/stars/salitaba/http2postman?style=social)](https://github.com/salitaba/http2postman)

![http2postman demo](https://raw.githubusercontent.com/salitaba/http2postman/main/docs/demo.gif)

```
  auth.http      ├── no runtime dependencies
  users.http     │
  orders.http    ▼
                 http2postman apis/ -o collection.json  →  Import into Postman ✓
```

## Why?

You built your whole API workflow in `.http` files — in IntelliJ, PyCharm,
Android Studio, or VS Code. Now a teammate uses Postman, or you need to share
requests with someone who does. Re-typing every request, header, and body by
hand is tedious and error-prone.

`http2postman` does the migration for you, in seconds:

```sh
pip install http2postman
http2postman requests.http
# ✓ 12 requests | 3 folders | 5 variables | 2 scripts → postman_collection.json
```

Then **Postman → Import → postman_collection.json** ([Postman docs](https://learning.postman.com/docs/sending-requests/import-export/importing-and-exporting-in-postman/)). Done.

## 30-second demo

**Before** — `requests.http` (JetBrains / REST Client format):

```http
@baseUrl = https://api.example.com

### List users
GET {{baseUrl}}/users
Authorization: Bearer {{token}}

### Create user
POST {{baseUrl}}/users
Content-Type: application/json

{ "name": "Ada", "role": "engineer" }

# @name login
POST {{baseUrl}}/auth/token
Content-Type: application/x-www-form-urlencoded
X-REQUEST-TYPE: GraphQL

{ "query": "{ viewer { login } }" }
```

**After** — `http2postman requests.http` produces a Postman v2.1 collection:

- ✅ Named requests (`### List users`, `# @name login`)
- ✅ `@baseUrl` → collection variable, `{{var}}` tokens preserved
- ✅ JSON / form / GraphQL / multipart / raw bodies
- ✅ `## Folder` / `@group` → Postman folders
- ✅ `Bearer` / `Basic` / OAuth2 headers → real Postman auth objects
- ✅ JetBrains `< {% %}` / `> {% %}` scripts → Pre-request / Tests tabs

## Features

- **Zero dependencies** — pure Python standard library, single CLI
- **Directories** — `http2postman apis/` recursively merges every `.http`/`.rest` file
- **Environments** — `--prefer-env dev` bakes JetBrains `http-client.env.json`
  values in as collection variable defaults
- **Dotenv** — `{{$dotenv VAR}}` resolves from the `.env` next to each file
- **Dynamic variables** — `$uuid` → `{{$guid}}`, `$random.email` →
  `{{$randomEmail}}`, and more
- **Honest reporting** — anything Postman can't express (WebSocket, gRPC) is
  listed with a `file:line` reference for manual porting; nothing is silently
  dropped
- **Safe** — source files are never modified; output is written atomically

## Installation

```sh
pip install http2postman
```

Or run directly from a checkout, no install needed:

```sh
python3 -m http2postman requests.http
```

On externally managed Python (Debian/Ubuntu, PEP 668), use a virtual
environment:

```sh
python3 -m venv .venv
source .venv/bin/activate   # note: `source`, not execute
pip install http2postman
```

## Usage

```sh
http2postman requests.http
http2postman requests.http -o my-collection.json
http2postman apis/ --name "My API"        # recursively scans directories
http2postman a.http b.http --quiet
http2postman requests.http --prefer-env dev   # bake env values into collection variables
http2postman requests.http --env-file .env    # resolve {{$dotenv VAR}} from a specific file
python3 -m http2postman requests.http
```

- `-o OUTPUT` — output JSON path (default `postman_collection.json` next to
  the input).
- `--name NAME` — collection name (default: first input file's basename).
- `--env-file PATH` — `.env` file for `{{$dotenv VAR}}` resolution (default:
  `.env` next to each input).
- `--prefer-env NAME` — use the named environment from `http-client.env.json`
  (plus `http-client.private.env.json`, merged on top) next to the first
  input as **default values** for the collection variables. Environment
  values win over file-level `@var` definitions; scalar values are coerced to
  strings, complex values to compact JSON. Missing file or environment name
  is an error (exit 1).
- `--quiet` — suppress the stdout report.

Exit codes: `0` success, `1` conversion/validation failure, `2` usage error.

Output is written atomically (temp file + rename); source files are never
modified.

### `--prefer-env` example

JetBrains environment files map environment names to variable values:

```json
// http-client.env.json (next to the .http file)
{
  "dev":  { "baseUrl": "https://dev.example.com",  "apiKey": "dev-key" },
  "prod": { "baseUrl": "https://prod.example.com" }
}
```

```sh
http2postman requests.http --prefer-env dev
# -> collection variables: baseUrl = https://dev.example.com, apiKey = dev-key
```

Secrets can live in `http-client.private.env.json` (same shape, merged on
top). The report prints `Env: dev` so you can see which environment was used.

## What is supported

- `###` request names, `# @name` overrides, all HTTP methods (unknown methods
  pass through verbatim)
- headers (order/casing preserved; `Content-Length` dropped)
- raw / JSON / XML / urlencoded / multipart / external-file bodies
- GraphQL requests via `X-REQUEST-TYPE: GraphQL`
- file-level `@name = value` variables -> collection variables
- `{{var}}` tokens (kept byte-for-byte), JetBrains dynamic variables mapped
  to Postman equivalents (`$uuid` -> `{{$guid}}`, `$random.email` ->
  `{{$randomEmail}}`, etc. — unlisted `$random.*` tokens are reported)
- `## Folder` / `@group` folder grouping
- **JetBrains environments**: `--prefer-env NAME` uses the values from
  `http-client.env.json` / `http-client.private.env.json` as the collection
  variable defaults (see the example above).
- **Scripts**: `< {% ... %}` pre-request and `> {% ... %}` response scripts
  are captured and translated into Postman Pre-request Script / Tests tabs
  (`client.test` -> `pm.test`, `client.assert` ->
  `pm.expect(!!(cond)[, msg]).to.be.true` — JetBrains' assert is a truthiness
  check, and Postman's sandbox does not support Chai's `.truthy`, so the
  condition is boolean-coerced with `!!()` and asserted with the documented
  `.to.be.true` —,
  `client.global.set` -> `pm.collectionVariables.set`,
  `request.variables.set` -> `pm.variables.set`,
  `request.environment.get` -> `pm.variables.get` (resolves through the
  Postman variable chain, so `--prefer-env` defaults are visible to scripts),
  `response.status` -> `pm.response.code`,
  `response.body` -> `pm.response.json()` (parsed body, like JetBrains),
  `response.contentType/time` -> `pm.response.*`, ...).
  Unrecognized statements pass through unchanged.
- **dotenv**: `{{$dotenv VAR}}` references resolve from the `.env` file next
  to each input file, or the file given with `--env-file`. Unresolved
  references are kept as-is and reported.
- **Auth**: OAuth2 client-credentials token requests (POST to a token/oauth
  URL with `grant_type=client_credentials` and a literal `Basic` header) get a
  Postman `oauth2` auth object; `Bearer {{token}}` and literal
  `Basic base64(user:pass)` / `Basic {{user}} {{pass}}` headers become
  `bearer` / `basic` auth objects (the header is removed).
- WebSocket (`WEBSOCKET ws://...`) and gRPC (`GRPC host/service/method`)
  blocks are parsed and reported under `Unsupported:` — the Postman Collection
  v2.1 schema cannot express them — with a `file:line` reference for manual
  porting.

The stdout report lists `Requests: N | Folders: N | Variables: N |
Scripts: N` (translated script blocks), an `Env:` line when `--prefer-env` is
used, and an `Unsupported:` line for anything left (unmapped dynamic
variables, unresolved dotenv refs, WebSocket/gRPC blocks).

## Convert .http to Postman from other tools

- **IntelliJ / PyCharm / WebStorm / Android Studio** — [HTTP Client](https://www.jetbrains.com/help/idea/http-client-in-product-code-editor.html)
  files under `src/test/http`, `http/`, or anywhere in the project.
  First-party export is still open as [IJPL-150186](https://youtrack.jetbrains.com/projects/IJPL/issues/IJPL-150186) —
  vote there; `http2postman` bridges the gap meanwhile.
- **VS Code REST Client** — [same syntax](https://marketplace.visualstudio.com/items?itemName=humao.rest-client),
  including `.rest` files
- **Bruno?** open an issue — multi-format support is planned

## Tests

```sh
python3 -m unittest discover -s tests
```

## Contributing

Issues and PRs are welcome — especially conversion edge cases from real-world
`.http` files. If output doesn't import into Postman, [open an
issue](https://github.com/salitaba/http2postman/issues) with a (redacted)
sample file.

## License

MIT — see [LICENSE](LICENSE).
