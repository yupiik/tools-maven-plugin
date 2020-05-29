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
package com.yupiik.maven.mojo;

import lombok.Setter;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.net.URI;

@Setter
@Mojo(name = "serve-slides")
public class ServeSlidesMojo extends SlidesMojo {
    @Parameter(property = "yupiik.slides.openBrowser", defaultValue = "true")
    private boolean openBrowser;

    @Override
    protected Mode getMode() {
        return Mode.SERVE;
    }

    @Override
    protected void onFirstRender() {
        if (!openBrowser) {
            return;
        }
        final URI uri = URI.create("http://localhost:" + port);
        if (!java.awt.Desktop.isDesktopSupported()) {
            getLog().info("Desktop is not supported on this JVM, go to " + uri + " in your browser");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(uri);
        } catch (final IOException e) {
            getLog().error("Desktop is not supported on this JVM, go to " + uri + " in your browser (" + e.getMessage() + ")");
        }
    }
}
