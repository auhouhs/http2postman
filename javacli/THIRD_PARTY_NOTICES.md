# Third-party notices

This project is a Java port of the MIT-licensed project **http2postman**
(upstream: https://github.com/salitaba/http2postman, Copyright (c) 2026
Ali Tabatabaei). The Python sources that were ported are kept, unmodified,
in [`py/`](py/) together with their original [`py/LICENSE`](py/LICENSE).

The port reproduces the upstream behaviour, including its public command-line
surface, its conversion semantics, and its test fixtures. The original MIT
copyright notice is therefore retained in [`LICENSE`](LICENSE); every Java
source file carries a header pointing back to it.

## Ported (original) project

| Component | Upstream file | License |
| --- | --- | --- |
| Data model | `py/http2postman/model.py` | MIT |
| `.http` parser | `py/http2postman/parser.py` | MIT |
| Postman converter | `py/http2postman/converter.py` | MIT |
| Auth detection | `py/http2postman/auth.py` | MIT |
| Script translation | `py/http2postman/scripts.py` | MIT |
| Environment files | `py/http2postman/envfile.py` | MIT |
| Collection validation | `py/http2postman/validation.py` | MIT |
| CLI | `py/http2postman/cli.py` | MIT |
| Unit tests and fixtures | `py/tests/` | MIT |

## Runtime dependencies

| Dependency | Version | License |
| --- | --- | --- |
| com.fasterxml.jackson.core:jackson-databind | 2.18.3 | Apache License 2.0 |

## Test dependencies

| Dependency | Version | License |
| --- | --- | --- |
| org.junit.jupiter:junit-jupiter | 5.13.4 | Eclipse Public License 2.0 |

## Trademarks

"IntelliJ", "PyCharm", "WebStorm", "Android Studio" and "JetBrains" are
trademarks of JetBrains s.r.o. "Postman" is a trademark of Postman, Inc.
This project is not affiliated with, endorsed by, or sponsored by either
company.
