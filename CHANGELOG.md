<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Http2postman Changelog

## [Unreleased]

> **Beta:** this version has not been fully tested. Bugs and edge-case failures are possible;
> review the generated collection and keep backups of your request files. Bug reports are welcome.

### Added

- "Convert to Postman Format File" action on `.http`/`.rest` files and folders (Project view and editor popup).
- Conversion dialog: output directory (defaults to the project root), file name, collection name,
  JetBrains environment (`http-client.env.json`), `.env` file, and folder-wide conversion.
- Background conversion with notifications reporting requests/folders/variables/scripts and
  constructs that a Postman collection cannot express.
- Programmatic conversion API (`com.auhouhs.http2postman.api`) used by the plugin and mirroring the CLI options.
