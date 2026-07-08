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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.edwmigration.dumper.application.dumper.connector.Connector;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectorRepository {

  private static final Logger logger = LoggerFactory.getLogger(ConnectorRepository.class);

  private static final List<ClassLoader> pluginClassLoaders = new CopyOnWriteArrayList<>();
  private static volatile ConnectorRepository instance;

  /**
   * Registers class loaders of connector plugins so that their connectors take part in discovery.
   * Must be called (at most once) before the first {@link #getInstance()}; connectors on the
   * application classpath are always discovered, so callers with no plugins need not call this.
   */
  public static void registerPluginClassLoaders(@Nonnull List<ClassLoader> classLoaders) {
    Preconditions.checkState(
        instance == null, "Plugin class loaders registered after connector discovery already ran");
    pluginClassLoaders.addAll(classLoaders);
  }

  public static ConnectorRepository getInstance() {
    ConnectorRepository result = instance;
    if (result == null) {
      synchronized (ConnectorRepository.class) {
        result = instance;
        if (result == null) {
          instance = result = new ConnectorRepository();
        }
      }
    }
    return result;
  }

  private final ImmutableMap<String, Connector> connectors;

  private ConnectorRepository() {
    Map<String, Connector> discovered = new LinkedHashMap<>();
    // The application classpath is scanned first so that, on a name clash, a classpath connector
    // shadows a plugin connector rather than the other way around.
    discover(ConnectorRepository.class.getClassLoader(), discovered);
    for (ClassLoader pluginClassLoader : pluginClassLoaders) {
      discover(pluginClassLoader, discovered);
    }
    connectors = ImmutableMap.copyOf(discovered);
  }

  @VisibleForTesting
  static void discover(ClassLoader classLoader, Map<String, Connector> out) {
    Iterator<Connector> iterator = ServiceLoader.load(Connector.class, classLoader).iterator();
    while (true) {
      try {
        if (!iterator.hasNext()) {
          break;
        }
        Connector connector = iterator.next();
        if (connector.getClass().getClassLoader() != classLoader) {
          // Plugin class loaders are parent-first, so a plugin scan re-surfaces every service
          // entry of the application classpath; only connectors owned by this loader are new.
          continue;
        }
        String name = connector.getName().toLowerCase();
        Connector previous = out.putIfAbsent(name, connector);
        if (previous != null && previous.getClass() != connector.getClass()) {
          logger.warn(
              "Duplicate connector '{}': {} shadows {}",
              name,
              previous.getClass().getName(),
              connector.getClass().getName());
        }
      } catch (ServiceConfigurationError e) {
        // One unloadable connector (e.g. a broken plugin jar) must not break discovery.
        logger.error("Skipping unloadable connector service: {}", e.toString());
      }
    }
  }

  ImmutableSet<String> getAllNames() {
    return connectors.keySet();
  }

  ImmutableCollection<Connector> getAllConnectors() {
    return connectors.values();
  }

  @Nullable
  public Connector getByName(@Nonnull String connectorName) {
    return connectors.get(connectorName.toLowerCase());
  }
}
