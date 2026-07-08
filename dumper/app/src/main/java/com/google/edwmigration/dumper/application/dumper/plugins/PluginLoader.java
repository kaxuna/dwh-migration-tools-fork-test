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
package com.google.edwmigration.dumper.application.dumper.plugins;

import com.google.common.annotations.VisibleForTesting;
import com.google.edwmigration.dumper.application.dumper.ConnectorRepository;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers connector plugins at startup. Each subdirectory of the plugins directory is loaded as
 * one plugin: all jars inside it form the classpath of a dedicated, parent-first {@link
 * URLClassLoader} whose parent is the application class loader. Discovered class loaders are
 * registered with {@link ConnectorRepository}, so {@code installPlugins()} must run before the
 * first use of the repository (including {@code --help}, which enumerates connectors).
 *
 * <p>The plugins directory is resolved in this order:
 *
 * <ol>
 *   <li>the {@code dumper.plugins.dir} system property,
 *   <li>the {@code DWH_MIGRATION_DUMPER_PLUGINS} environment variable,
 *   <li>{@code <app home>/plugins}, where the app home is derived from the location of the jar
 *       containing this class (the distribution places jars in {@code <app home>/lib}).
 * </ol>
 */
public final class PluginLoader {

  private static final Logger logger = LoggerFactory.getLogger(PluginLoader.class);

  public static final String PLUGINS_DIR_PROPERTY = "dumper.plugins.dir";
  public static final String PLUGINS_DIR_ENV = "DWH_MIGRATION_DUMPER_PLUGINS";

  private PluginLoader() {}

  /** Loads all plugins and registers them with {@link ConnectorRepository}. Never throws. */
  public static void installPlugins() {
    Path pluginsDir = resolvePluginsDir();
    if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
      logger.debug("No plugins directory at {}; using classpath connectors only.", pluginsDir);
      ConnectorRepository.registerPluginClassLoaders(Collections.<ClassLoader>emptyList());
      return;
    }
    ConnectorRepository.registerPluginClassLoaders(loadPluginClassLoaders(pluginsDir));
  }

  @Nonnull
  @VisibleForTesting
  public static List<ClassLoader> loadPluginClassLoaders(@Nonnull Path pluginsDir) {
    List<ClassLoader> classLoaders = new ArrayList<>();
    try (DirectoryStream<Path> pluginDirs = Files.newDirectoryStream(pluginsDir)) {
      for (Path pluginDir : pluginDirs) {
        if (!Files.isDirectory(pluginDir)) {
          continue;
        }
        try {
          classLoaders.add(newPluginClassLoader(pluginDir));
          logger.debug("Loaded plugin '{}'.", pluginDir.getFileName());
        } catch (Exception e) {
          // A broken plugin must not prevent the application (or other plugins) from working.
          logger.error("Skipping broken plugin '{}': {}", pluginDir.getFileName(), e.toString());
        }
      }
    } catch (IOException e) {
      logger.error("Cannot read plugins directory {}: {}", pluginsDir, e.toString());
    }
    return classLoaders;
  }

  @Nonnull
  private static ClassLoader newPluginClassLoader(@Nonnull Path pluginDir) throws IOException {
    List<URL> jars = new ArrayList<>();
    try (DirectoryStream<Path> jarPaths = Files.newDirectoryStream(pluginDir, "*.jar")) {
      for (Path jar : jarPaths) {
        jars.add(jar.toUri().toURL());
      }
    }
    if (jars.isEmpty()) {
      throw new IOException("No jars in plugin directory " + pluginDir);
    }
    return new URLClassLoader(jars.toArray(new URL[0]), PluginLoader.class.getClassLoader());
  }

  @Nullable
  @VisibleForTesting
  public static Path resolvePluginsDir() {
    String override = System.getProperty(PLUGINS_DIR_PROPERTY);
    if (override == null) {
      override = System.getenv(PLUGINS_DIR_ENV);
    }
    if (override != null) {
      return Paths.get(override);
    }
    try {
      // In the distribution this class lives in <app home>/lib/<app>.jar.
      Path jar =
          Paths.get(PluginLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path lib = jar.getParent();
      if (lib == null || lib.getParent() == null) {
        return null;
      }
      return lib.getParent().resolve("plugins");
    } catch (Exception e) {
      logger.debug("Cannot derive app home from code source: {}", e.toString());
      return null;
    }
  }
}
