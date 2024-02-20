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
import java.util.ArrayList;
import java.util.Collection;

public class CollectionJsonCodec {
    private final ObjectJsonCodec delegate;

    public CollectionJsonCodec(final ObjectJsonCodec delegate) {
        this.delegate = delegate;
    }

    public Collection<Object> read(final JsonParser reader) throws IOException {
        if (!reader.hasNext()) {
            throw new IllegalStateException("No more element");
        }

        final var next = reader.next();
        if (next != JsonParser.Event.START_ARRAY) {
            throw new IllegalStateException("Expected=START_ARRAY, but got " + next);
        }

        final var instance = new ArrayList<>();
        JsonParser.Event event;
        while (reader.hasNext() && (event = reader.next()) != JsonParser.Event.END_ARRAY) {
            reader.rewind(event);
            instance.add(delegate.read(reader));
        }

        return instance;
    }
}
