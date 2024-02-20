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
package io.yupiik.tools.common.asciidoctor;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public interface AsciidoctorConfiguration {
    Path gems();

    String customGems();

    List<String> requires();

    Consumer<String> info();

    Consumer<String> debug();

    Consumer<String> warn();

    Consumer<String> error();
}