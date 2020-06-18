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
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;
import org.scilab.forge.jlatexmath.cyrillic.CyrillicRegistration;
import org.scilab.forge.jlatexmath.greek.GreekRegistration;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static lombok.AccessLevel.PRIVATE;

@NoArgsConstructor(access = PRIVATE)
class Render {
    static String render(final String latex) {
        DefaultTeXFont.registerAlphabet(new CyrillicRegistration());
        DefaultTeXFont.registerAlphabet(new GreekRegistration());

        final TeXFormula formula = new TeXFormula(latex);
        final TeXIcon icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, 20);
        icon.setInsets(new Insets(5, 5, 5, 5));

        final int w = icon.getIconWidth(), h = icon.getIconHeight();

        final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2 = image.createGraphics();

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        icon.setForeground(Color.BLACK);
        icon.paintIcon(null, g2, 0, 0);
        try {
            ImageIO.write(image, "png", buffer);
            buffer.flush();
            buffer.close();
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }

        g2.dispose();
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(buffer.toByteArray());
    }
}

public interface JLatexMath {
    @Name("jlatexmath")
    @Contexts(Contexts.OPEN)
    @ContentModel(ContentModel.COMPOUND)
    class Block extends BlockProcessor {
        @Override
        public Object process(final StructuralNode parent, final Reader reader, final Map<String, Object> attributes) {
            return createBlock(parent, "open", "" +
                    "image::" +
                    Render.render(String.join("\n", reader.lines()).trim()) +
                    "[" + attributes.getOrDefault("opts", "") + "]");
        }
    }

    @Name("jmath")
    class Inline extends InlineMacroProcessor {
        @Override
        public Object process(final ContentNode parent, final String target, final Map<String, Object> attributes) {
            final String image = Render.render(String.valueOf(attributes.values().iterator().next()));
            return createPhraseNode(parent, "image", "", singletonMap("alt", ""), new HashMap<>(singletonMap("target", image)));
        }
    }
}
