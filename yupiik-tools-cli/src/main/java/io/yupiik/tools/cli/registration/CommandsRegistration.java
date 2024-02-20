/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.tools.cli.registration;

import io.yupiik.tools.cli.command.MinisiteCommand;
import io.yupiik.tools.cli.command.SlidesCommand;
import io.yupiik.tools.cli.command.VersionInjectorCommand;
import io.yupiik.tools.cli.converter.Converters;
import io.yupiik.tools.cli.service.AsciidoctorProvider;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import io.yupiik.tools.minisite.PreAction;
import io.yupiik.tools.slides.SlidesConfiguration;
import org.tomitribe.crest.api.Loader;

import java.beans.PropertyEditorManager;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class CommandsRegistration implements Loader {
    static {
        if (PropertyEditorManager.findEditor(Path.class) == null) {
            PropertyEditorManager.registerEditor(Path.class, Converters.PathConverter.class);
        }
        if (PropertyEditorManager.findEditor(Charset.class) == null) {
            PropertyEditorManager.registerEditor(Charset.class, Converters.CharsetConverter.class);
        }
        if (PropertyEditorManager.findEditor(Map.class) == null) {
            PropertyEditorManager.registerEditor(Map.class, Converters.MapConverter.class);
        }
        PropertyEditorManager.registerEditor(MiniSiteConfiguration.BlogCategoryConfiguration.class, Converters.BlogCategoryConfigurationConverter.class);
        PropertyEditorManager.registerEditor(PreAction.class, Converters.PreActionConverter.class);
        PropertyEditorManager.registerEditor(SlidesConfiguration.Synchronization.class, Converters.SynchronizationConverter.class);
    }

    @Override
    public Iterator<Class<?>> iterator() {
        return List.of(MinisiteCommand.class, SlidesCommand.class, AsciidoctorProvider.class, VersionInjectorCommand.class).iterator();
    }
}
