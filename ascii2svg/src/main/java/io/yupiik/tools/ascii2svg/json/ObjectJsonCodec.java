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

// used to handle unknown structures
public class ObjectJsonCodec {
    private final CollectionJsonCodec collectionCodec = new CollectionJsonCodec(this);
    private final MapJsonCodec mapCodec = new MapJsonCodec(this);

    public Object read(final JsonParser parser) throws IOException {
        if (!parser.hasNext()) {
            return null;
        }

        final var next = parser.next();
        switch (next) {
            case VALUE_NULL:
                return null;
            case VALUE_TRUE:
                return true;
            case VALUE_FALSE:
                return false;
            case VALUE_STRING:
                return parser.getString();
            case VALUE_NUMBER:
                return parser.getBigDecimal();
            case START_OBJECT:
                parser.rewind(next);
                return mapCodec.read(parser);
            case START_ARRAY:
                parser.rewind(next);
                return collectionCodec.read(parser);
            default:
                throw new IllegalStateException("Invalid event: " + next);
        }
    }
}
