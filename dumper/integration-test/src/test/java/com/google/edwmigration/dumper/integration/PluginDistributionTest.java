/*
 * Copyright 2022-2025 Google LLC
 * Copyright 2013-2021 CompilerWorks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.edwmigration.dumper.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Regression gate for the plugin distribution. Runs against the installed published layout (see the
 * dumper.dist.dir system property wired in build.gradle).
 */
@RunWith(JUnit4.class)
public class PluginDistributionTest {

  private static final Set<String> EXPECTED_PLUGINS =
      new TreeSet<>(
          Arrays.asList(
              "airflow",
              "bigquery",
              "cloudera",
              "generic",
              "greenplum",
              "hadoop",
              "hdfs",
              "hive",
              "mysql",
              "netezza",
              "oracle",
              "postgresql",
              "ranger",
              "redshift",
              "snowflake",
              "sqlserver",
              "teradata",
              "vertica"));

  /** Frozen from the pre-restructure baseline; a dropped name here is a released regression. */
  private static final Set<String> EXPECTED_CONNECTORS =
      new TreeSet<>(
          Arrays.asList(
              "airflow",
              "bigquery",
              "bigquery-logs",
              "cloudera-manager",
              "generic",
              "greenplum",
              "greenplum-logs",
              "hadoop",
              "hdfs",
              "hiveql",
              "mysql",
              "netezza",
              "oozie",
              "oracle",
              "oracle-logs",
              "oracle-stats",
              "postgresql",
              "ranger",
              "redshift",
              "redshift-logs",
              "redshift-raw-logs",
              "redshift-serverless-logs",
              "snowflake",
              "snowflake-account-usage-logs",
              "snowflake-account-usage-metadata",
              "snowflake-information-schema-logs",
              "snowflake-information-schema-metadata",
              "snowflake-logs",
              "sqlserver",
              "teradata",
              "teradata-logs",
              "teradata14-logs",
              "vertica",
              "vertica-logs"));

  private static final String SERVICES_ENTRY =
      "META-INF/services/"
          + "com.google.edwmigration.dumper.application.dumper.connector.Connector";

  private static File distDir;

  @BeforeClass
  public static void setUp() {
    String dir = System.getProperty("dumper.dist.dir");
    assertTrue("dumper.dist.dir system property must be set", dir != null && !dir.isEmpty());
    distDir = new File(dir);
    assertTrue("Installed distribution exists at " + distDir, distDir.isDirectory());
  }

  private static File[] pluginDirs() {
    File[] dirs = new File(distDir, "plugins").listFiles(File::isDirectory);
    return dirs == null ? new File[0] : dirs;
  }

  @Test
  public void pluginsDirectory_hasExactlyTheExpectedVendors_allNonEmpty() {
    Set<String> actual = new TreeSet<>();
    for (File dir : pluginDirs()) {
      File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
      assertTrue(
          "Plugin directory " + dir.getName() + " contains jars", jars != null && jars.length > 0);
      actual.add(dir.getName());
    }
    assertEquals(EXPECTED_PLUGINS, actual);
  }

  @Test
  public void everyPluginDirectory_hasAConnectorServiceProvider() throws Exception {
    for (File dir : pluginDirs()) {
      boolean found = false;
      File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
      for (File jar : jars) {
        try (ZipFile zip = new ZipFile(jar)) {
          ZipEntry entry = zip.getEntry(SERVICES_ENTRY);
          if (entry != null) {
            found = true;
            break;
          }
        }
      }
      assertTrue("Plugin " + dir.getName() + " provides a Connector service entry", found);
    }
  }

  @Test
  public void pluginDirectories_carryNoLoggingBackendAndNoCoreDuplicate() {
    Set<String> coreJars = new HashSet<>();
    File[] libJars = new File(distDir, "lib").listFiles((d, name) -> name.endsWith(".jar"));
    assertTrue("Core lib/ directory has jars", libJars != null && libJars.length > 0);
    for (File jar : libJars) {
      coreJars.add(jar.getName());
    }
    for (File dir : pluginDirs()) {
      for (File jar : dir.listFiles((d, name) -> name.endsWith(".jar"))) {
        String name = jar.getName();
        assertFalse(
            "No logging backend in plugin " + dir.getName() + ": " + name,
            name.startsWith("logback-")
                || name.startsWith("slf4j-simple")
                || name.startsWith("slf4j-reload4j")
                || name.startsWith("slf4j-log4j12"));
        assertFalse(
            "Plugin " + dir.getName() + " duplicates core jar " + name, coreJars.contains(name));
      }
    }
  }

  @Test
  public void helpOutput_listsEveryFrozenConnector_endToEnd() throws Exception {
    File script = new File(distDir, "bin/dwh-migration-dumper");
    assertTrue("Startup script exists", script.isFile());

    ProcessBuilder processBuilder = new ProcessBuilder(script.getAbsolutePath(), "--help");
    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    processBuilder.redirectErrorStream(true);
    Process process = processBuilder.start();

    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    assertTrue("--help finished in time", process.waitFor(120, TimeUnit.SECONDS));
    // --help historically exits 1 (usage-exception path); the connector list below is the gate.

    Pattern connectorLine = Pattern.compile("^\\* ([a-z][a-z0-9-]*)\\b.*");
    Set<String> connectors = new TreeSet<>();
    for (String line : lines) {
      Matcher matcher = connectorLine.matcher(line);
      if (matcher.matches()) {
        connectors.add(matcher.group(1));
      }
    }
    assertEquals(EXPECTED_CONNECTORS, connectors);
  }
}
