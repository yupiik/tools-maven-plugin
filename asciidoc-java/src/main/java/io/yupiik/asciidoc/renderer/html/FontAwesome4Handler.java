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
package io.yupiik.asciidoc.renderer.html;

/**
 * Font Awesome 4, {@code fa fa-lock}: what asciidoctor writes with {@code icons=font}; picked with {@code font-awesome-4}.
 */
public class FontAwesome4Handler extends FontAwesomeHandler {
    /**
     * The cdnjs stylesheet of version 4.7.0, asciidoctor's default {@code iconfont-cdn}, which the page also links
     * for a handler without a {@link #stylesheet()}.
     */
    public static final String STYLESHEET = "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/4.7.0/css/font-awesome.min.css";

    public FontAwesome4Handler() {
        super("fa");
    }

    @Override
    public String stylesheet() {
        return STYLESHEET;
    }
}
