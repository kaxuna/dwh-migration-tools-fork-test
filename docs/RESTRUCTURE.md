# Dumper modularization: old → new layout

The dumper was restructured from a single `dumper/app` module into a core CLI plus per-vendor
connector plugin modules loaded at runtime. Java package names did not change anywhere — files
moved between Gradle modules only. This map exists to ease merging upstream changes.

## Module map

| Old location (in `dumper/app`) | New location |
| --- | --- |
| `.../application/dumper/connector/<vendor>/**` | `dumper/connectors/<vendor>/src/main/java/...` (same package) |
| vendor tests `.../connector/<vendor>/**` | `dumper/connectors/<vendor>/src/test/java/...` |
| `ConnectorArguments`, `ConnectorRepository`, `ConnectorProperties`, `DefaultArguments`, `MetadataDumperUsageException`, `ZonedParser`, `InputDescriptor`, `SummaryPrinter`, `StartUpMetaInfoProcessor` | `dumper/connector-api` (same packages) |
| `annotations/`, `task/`, `handle/` (minus `RedshiftHandle`), `io/`, `utils/`, `metrics/`, `connector/` base classes | `dumper/connector-api` |
| `handle/RedshiftHandle` | `dumper/connectors/redshift` (package unchanged) |
| `AbstractConnectorTest`, `AbstractJdbcConnectorTest` | `dumper/connector-api` testFixtures |
| `AbstractConnectorExecutionTest`, `TestConnector`, `DumperTestUtils`, `DummyByteSinkFactory`, `MemoryByteSink`, `ResourceLocation` | `dumper/app` testFixtures |
| resources `oracle-stats/`, `snowflake-features/`, `hadoop-scripts/` | respective connector modules |

18 connector modules: airflow, bigquery, cloudera, generic, greenplum, hadoop (incl. oozie),
hdfs, hive, mysql, netezza, oracle, postgresql, ranger, redshift, snowflake, sqlserver,
teradata, vertica.

## Routing upstream `dumper/app/build.gradle` dependency changes

Vendor-specific dependencies now live in the vendor module's `build.gradle`:

| Dependency | Module |
| --- | --- |
| postgresql driver | `connectors/postgresql` |
| snowflake-jdbc, jna, jackson | `connectors/snowflake` |
| redshift-jdbc, aws-java-sdk-* | `connectors/redshift` |
| hadoop-common, hadoop-hdfs-client, hadoop-auth + CVE pins (jetty, netty, dnsjava, nimbus, zookeeper, configuration2, bouncycastle) | `connectors/hdfs` |
| oozie-client, xerces | `connectors/hadoop` |
| google-cloud-bigquery, grpc pins, rate-limited-logger, lib-ext-bigquery | `connectors/bigquery` |
| lib-ext-hive-metastore (+ datanucleus test deps) | `connectors/hive` |
| httpclient 4/5 | `connectors/cloudera` |
| hadoop-auth, httpclient4 | `connectors/ranger` |

Everything else (guava, jackson, spring, jopt-simple, logback, google-cloud-nio/kms, …) stays
in `dumper/app` or `dumper/connector-api`.

## Runtime plugin system

- `ConnectorRepository` (connector-api) discovers connectors from the application classpath
  first, then from class loaders registered by `PluginLoader` (app) — one parent-first
  `URLClassLoader` per `plugins/<vendor>/` directory.
- Plugins directory resolution: `-Ddumper.plugins.dir`, then `$DWH_MIGRATION_DUMPER_PLUGINS`,
  then `<app home>/plugins`.
- `MetadataDumper` runs each connector with its plugin class loader as the thread context class
  loader so JDBC drivers and classpath resources resolve from the plugin's jars.
- The published zip assembles `plugins/<vendor>/` from each connector module's runtime classpath
  minus every `group:artifact` the core `lib/` already ships (`assemblePlugins` in
  `dumper/app/build.gradle`).
- To slim a deployment, delete unwanted `plugins/<vendor>/` directories.
- To add a connector out-of-tree: implement `Connector` (annotate with
  `@AutoService(Connector.class)` or provide the `META-INF/services` entry), compile against
  `connector-api`, and drop the jar(s) into a new `plugins/<name>/` directory.
- Regression gate: `:dumper:integration-test` asserts the plugin layout and that `--help` from
  the installed published distribution lists exactly the 34 baseline connector names.
