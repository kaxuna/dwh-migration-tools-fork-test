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
package foo.plug;

import com.google.edwmigration.dumper.application.dumper.ConnectorArguments;
import com.google.edwmigration.dumper.application.dumper.connector.AbstractConnector;
import com.google.edwmigration.dumper.application.dumper.handle.Handle;
import com.google.edwmigration.dumper.application.dumper.task.Task;
import java.time.Clock;
import java.util.List;

/** Compiled at test time into a synthetic plugin jar; not part of the production build. */
public class TestPluginConnector extends AbstractConnector {

  public TestPluginConnector() {
    super("test-plugin-connector");
  }

  @Override
  public String getDefaultFileName(boolean isAssessment, Clock clock) {
    return "";
  }

  @Override
  public void addTasksTo(List<? super Task<?>> out, ConnectorArguments arguments) {}

  @Override
  public Handle open(ConnectorArguments arguments) {
    return () -> {};
  }
}
