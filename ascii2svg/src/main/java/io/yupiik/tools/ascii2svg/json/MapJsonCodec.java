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
package io.yupiik.tools.ascii2svg.json;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class MapJsonCodec {
    private final ObjectJsonCodec delegate;

    public MapJsonCodec(final ObjectJsonCodec delegate) {
        this.delegate = delegate;
    }

    public Map<String, Object> read(final JsonParser reader) throws IOException {
        reader.enforceNext(JsonParser.Event.START_OBJECT);

        final var instance = new LinkedHashMap<String, Object>();
        JsonParser.Event event;
        while (reader.hasNext() && (event = reader.next()) != JsonParser.Event.END_OBJECT) {
            reader.rewind(event);

            final var keyEvent = reader.next();
            if (keyEvent != JsonParser.Event.KEY_NAME) {
                throw new IllegalStateException("Expected=KEY_NAME, but got " + keyEvent);
            }
            instance.put(reader.getString(), delegate.read(reader));
        }
        return instance;
    }
}
