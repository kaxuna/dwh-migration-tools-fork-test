# BigQuery Migration Service Metadata and Log Dumper

This directory contains the Metadata and Log Dumper, a command line tool for
connecting to an existing database and generating an archive of DDL metadata or
logs. This tool generates archives in a format suitable for consumption by the
[BigQuery Migration Service's][BQMS] Assessment or Translation Service.

The Dumper is a Java tool. **[Download the latest cross-platform release zip `dwh-migration-tools-vX.X.X.zip`.](https://github.com/google/dwh-migration-tools/releases/latest)**

Compiling the Dumper from source requires `Java 8`, running the Dumper requires `Java 8` or higher. To check Java version run the command
`java -version` or refer to Java vendor documentation. Third party JDBC drivers
might impose additional restrictions on Java versions. Refer to the JDBC
driver's manual for details.

To get started using the Dumper, read
[the documentation](https://cloud.google.com/bigquery/docs/generate-metadata).


[BQMS]: https://cloud.google.com/bigquery/docs/migration-intro

## Connector plugins

Since the modular restructuring, the Dumper ships each connector as a runtime plugin: the
distribution zip contains a slim `lib/` (the core CLI) and one `plugins/<vendor>/` directory
per connector with that vendor's jars. Behavior and the command line are unchanged.

- **Slim a deployment:** delete the `plugins/<vendor>/` directories you do not need.
- **Custom plugins location:** set `-Ddumper.plugins.dir=<path>` or the
  `DWH_MIGRATION_DUMPER_PLUGINS` environment variable; the default is `plugins/` next to
  `bin/` and `lib/`.
- **Write a connector without forking:** implement
  `com.google.edwmigration.dumper.application.dumper.connector.Connector` against the
  `connector-api` module, register it via `META-INF/services` (or `@AutoService`), and drop
  the jar plus its vendor-specific dependencies into a new `plugins/<name>/` directory.

See [docs/RESTRUCTURE.md](../docs/RESTRUCTURE.md) for the module map and design details.
