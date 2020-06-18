/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com - All right reserved
 *
 * This software and related documentation are provided under a license agreement containing restrictions on use and
 * disclosure and are protected by intellectual property laws. Except as expressly permitted in your license agreement
 * or allowed by law, you may not use, copy, reproduce, translate, broadcast, modify, license, transmit, distribute,
 * exhibit, perform, publish, or display any part, in any form, or by any means. Reverse engineering, disassembly, or
 * decompilation of this software, unless required by law for interoperability, is prohibited.
 *
 * The information contained herein is subject to change without notice and is not warranted to be error-free. If you
 * find any errors, please report them to us in writing.
 *
 * This software is developed for general use in a variety of information management applications. It is not developed
 * or intended for use in any inherently dangerous applications, including applications that may create a risk of personal
 * injury. If you use this software or hardware in dangerous applications, then you shall be responsible to take all
 * appropriate fail-safe, backup, redundancy, and other measures to ensure its safe use. Yupiik SAS and its affiliates
 * disclaim any liability for any damages caused by use of this software or hardware in dangerous applications.
 *
 * Yupiik and Galaxy are registered trademarks of Yupiik SAS and/or its affiliates. Other names may be trademarks
 * of their respective owners.
 *
 * This software and documentation may provide access to or information about content, products, and services from third
 * parties. Yupiik SAS and its affiliates are not responsible for and expressly disclaim all warranties of any kind with
 * respect to third-party content, products, and services unless otherwise set forth in an applicable agreement between
 * you and Yupiik SAS. Yupiik SAS and its affiliates will not be responsible for any loss, costs, or damages incurred
 * due to your access to or use of third-party content, products, or services, except as set forth in an applicable
 * agreement between you and Yupiik SAS.
 */
package com.yupiik.maven.service.extension;

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
