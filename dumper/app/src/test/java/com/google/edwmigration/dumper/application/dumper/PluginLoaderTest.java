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
package com.google.edwmigration.dumper.application.dumper;

import com.google.common.io.Resources;
import com.google.edwmigration.dumper.application.dumper.connector.Connector;
import com.google.edwmigration.dumper.application.dumper.plugins.PluginLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PluginLoaderTest {

  private static final String SERVICES_ENTRY = "META-INF/services/" + Connector.class.getName();

  private static final Path RESOURCES =
      Paths.get(Resources.getResource("plugin-loader-test/").getPath());

  private static Path pluginsDir;

  @BeforeClass
  public static void setUp() throws IOException {
    compile("foo/plug/TestPluginConnector", "foo/plug/DupeConnectorA", "foo/plug/DupeConnectorB");

    pluginsDir = Files.createTempDirectory("plugins-test");

    // A well-formed plugin: one jar with three connectors, a dangling service entry included.
    Path goodPlugin = Files.createDirectory(pluginsDir.resolve("good"));
    String services =
        "foo.plug.MissingConnector\n" // not in the jar: must be skipped, not fatal
            + "foo.plug.TestPluginConnector\n"
            + "foo.plug.DupeConnectorA\n"
            + "foo.plug.DupeConnectorB\n"; // same connector name as A: A must win
    buildJar(
        goodPlugin.resolve("connector.jar"),
        services,
        "foo/plug/TestPluginConnector",
        "foo/plug/DupeConnectorA",
        "foo/plug/DupeConnectorB");

    // Broken plugins: no jars at all, and a file where a directory is expected.
    Files.createDirectory(pluginsDir.resolve("empty"));
    Path junkPlugin = Files.createDirectory(pluginsDir.resolve("junk"));
    Files.write(junkPlugin.resolve("readme.txt"), "not a jar".getBytes(StandardCharsets.UTF_8));
    Files.write(pluginsDir.resolve("stray-file"), new byte[0]);
  }

  private static void compile(String... classNames) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    java.util.List<String> options =
        java.util.Arrays.asList("-classpath", System.getProperty("java.class.path"));
    java.util.List<java.io.File> sources = new java.util.ArrayList<>();
    for (String className : classNames) {
      sources.add(RESOURCES.resolve(className + ".java").toFile());
    }
    Iterable<? extends JavaFileObject> files =
        compiler.getStandardFileManager(null, null, null).getJavaFileObjectsFromFiles(sources);
    Boolean ok = compiler.getTask(null, null, null, options, null, files).call();
    Assert.assertTrue("Test connector sources compiled", ok);
  }

  private static void buildJar(Path jarPath, String services, String... classNames)
      throws IOException {
    try (JarOutputStream jar =
        new JarOutputStream(Files.newOutputStream(jarPath), new Manifest())) {
      for (String className : classNames) {
        jar.putNextEntry(new JarEntry(className + ".class"));
        Files.copy(RESOURCES.resolve(className + ".class"), jar);
      }
      jar.putNextEntry(new JarEntry(SERVICES_ENTRY));
      jar.write(services.getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  public void loadPluginClassLoaders_loadsGoodSkipsBroken() {
    List<ClassLoader> loaders = PluginLoader.loadPluginClassLoaders(pluginsDir);

    // Only the "good" plugin yields a class loader; "empty", "junk" and the stray file do not.
    Assert.assertEquals(1, loaders.size());
  }

  @Test
  public void discover_findsPluginConnectors_dedupesByName_skipsUnloadable() {
    List<ClassLoader> loaders = PluginLoader.loadPluginClassLoaders(pluginsDir);
    Map<String, Connector> connectors = new LinkedHashMap<>();
    ConnectorRepository.discover(loaders.get(0), connectors);

    Assert.assertTrue(
        "Plugin connector discovered", connectors.containsKey("test-plugin-connector"));
    Assert.assertTrue(
        "Dupe-named connector discovered once", connectors.containsKey("dupe-plugin"));
    Assert.assertEquals(
        "First provider wins on a name clash",
        "foo.plug.DupeConnectorA",
        connectors.get("dupe-plugin").getClass().getName());
    Assert.assertEquals(
        "Dangling service entry skipped without aborting discovery", 2, connectors.size());
  }

  @Test
  public void discover_classpathConnectorShadowsPluginConnector() {
    List<ClassLoader> loaders = PluginLoader.loadPluginClassLoaders(pluginsDir);
    Map<String, Connector> connectors = new HashMap<>();

    // Simulate the repository's ordering: classpath scan first, then plugins.
    ConnectorRepository.discover(PluginLoaderTest.class.getClassLoader(), connectors);
    Connector classpathTeradata = connectors.get("teradata");
    Assert.assertNotNull("Classpath connectors discovered", classpathTeradata);

    ConnectorRepository.discover(loaders.get(0), connectors);
    Assert.assertSame(
        "Classpath connector not displaced by plugin scan",
        classpathTeradata,
        connectors.get("teradata"));
  }

  @Test
  public void loadPluginClassLoaders_missingDirectory_returnsEmpty() {
    List<ClassLoader> loaders =
        PluginLoader.loadPluginClassLoaders(pluginsDir.resolve("does-not-exist"));
    Assert.assertTrue(loaders.isEmpty());
  }

  @Test
  public void resolvePluginsDir_systemPropertyWins() {
    String previous = System.setProperty(PluginLoader.PLUGINS_DIR_PROPERTY, "/tmp/some-plugins");
    try {
      Assert.assertEquals(Paths.get("/tmp/some-plugins"), PluginLoader.resolvePluginsDir());
    } finally {
      if (previous == null) {
        System.clearProperty(PluginLoader.PLUGINS_DIR_PROPERTY);
      } else {
        System.setProperty(PluginLoader.PLUGINS_DIR_PROPERTY, previous);
      }
    }
  }

  @Test
  public void registerPluginClassLoaders_afterDiscovery_throws() {
    ConnectorRepository.getInstance(); // force discovery
    Assert.assertThrows(
        IllegalStateException.class,
        () ->
            ConnectorRepository.registerPluginClassLoaders(
                java.util.Collections.<ClassLoader>emptyList()));
  }
}
