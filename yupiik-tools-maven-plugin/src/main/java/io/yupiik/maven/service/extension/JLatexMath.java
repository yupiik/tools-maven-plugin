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
package io.yupiik.maven.service.extension;

import lombok.NoArgsConstructor;
import org.asciidoctor.ast.ContentModel;
import org.asciidoctor.ast.ContentNode;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.extension.BlockProcessor;
import org.asciidoctor.extension.Contexts;
import org.asciidoctor.extension.InlineMacroProcessor;
import org.asciidoctor.extension.Name;
import org.asciidoctor.extension.Reader;
import org.scilab.forge.jlatexmath.DefaultTeXFont;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;
import org.scilab.forge.jlatexmath.cyrillic.CyrillicRegistration;
import org.scilab.forge.jlatexmath.greek.GreekRegistration;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static lombok.AccessLevel.PRIVATE;

@NoArgsConstructor(access = PRIVATE)
class Render {
    static String render(final String latex, final int size, final int style) {
        DefaultTeXFont.registerAlphabet(new CyrillicRegistration());
        DefaultTeXFont.registerAlphabet(new GreekRegistration());

        final TeXIcon icon = new TeXFormula(latex).createTeXIcon(style, size);
        icon.setInsets(new Insets(0, 0, 0, 0));

        final int weight = icon.getIconWidth();
        final int height = icon.getIconHeight();

        final BufferedImage image = new BufferedImage(weight, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        icon.setForeground(Color.BLACK);
        icon.paintIcon(null, graphics, 0, 0);
        try {
            ImageIO.write(image, "png", buffer);
            buffer.flush();
            buffer.close();
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }

        graphics.dispose();
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(buffer.toByteArray());
    }
}

public interface JLatexMath {
    @Name("jlatexmath")
    @Contexts(Contexts.OPEN)
    @ContentModel(ContentModel.ATTRIBUTES)
    class Block extends BlockProcessor {
        @Override
        public Object process(final StructuralNode parent, final Reader reader, final Map<String, Object> attributes) {
            return createBlock(parent, "open", "" +
                    "image::" +
                    Render.render(String.join("\n", reader.lines()).trim(),
                            Integer.parseInt(String.valueOf(attributes.getOrDefault("size", "30"))),
                            Integer.parseInt(String.valueOf(attributes.getOrDefault("style", "0")))) +
                    "[" + attributes.entrySet().stream()
                    .filter(e -> !"1".equals(e.getKey()) && !"cloaked-context".equals(e.getKey()))
                    .map(e -> e.getKey() + "=\"" + String.valueOf(e.getValue()).replace("\"", "\\\"") + "\"")
                    .collect(joining(",")) + "]");
        }
    }

    @Name("jmath")
    class Inline extends InlineMacroProcessor {
        @Override
        public Object process(final ContentNode parent, final String target, final Map<String, Object> attributes) {
            final String image = Render.render(extractString(attributes),
                    Integer.parseInt(String.valueOf(attributes.getOrDefault("size", "30"))),
                    Integer.parseInt(String.valueOf(attributes.getOrDefault("style", "2"))));
            final Map<String, Object> newAttributes = attributes.entrySet().stream()
                    .filter(e -> {
                        if ("size".equals(e.getKey()) || "style".equals(e.getValue())) {
                            return false;
                        }
                        try {
                            Integer.parseInt(e.getKey());
                            return false;
                        } catch (final NumberFormatException nfe) {
                            return true;
                        }
                    })
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            newAttributes.putIfAbsent("alt", "");
            return createPhraseNode(parent, "image", "", newAttributes, new HashMap<>(singletonMap("target", image)));
        }

        private String extractString(final Map<String, Object> attributes) {
            return attributes.entrySet().stream()
                    .filter(it -> {
                        try {
                            Integer.parseInt(it.getKey());
                            return true;
                        } catch (final NumberFormatException nfe) {
                            return false;
                        }
                    })
                    .map(it -> new AbstractMap.SimpleImmutableEntry<>(Integer.parseInt(it.getKey()), String.valueOf(it.getValue())))
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .collect(joining(","));
        }
    }
}
