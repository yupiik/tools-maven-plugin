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
package io.yupiik.tools.slides.slider;

import java.util.LinkedHashMap;
import java.util.Map;

public class RevealjsSlider implements Slider {
    @Override
    public Map<String, Object> append(final Map<String, Object> attributes) {
        final Map<String, Object> copy = new LinkedHashMap<>(attributes);
        copy.put("revealjsdir", "//cdnjs.cloudflare.com/ajax/libs/reveal.js/3.8.0/");
        copy.put("revealjs_theme", "black");
        copy.put("revealjs_transition", "linear");
        return copy;
    }
}
