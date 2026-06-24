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

import io.yupiik.asciidoc.model.Admonition;
import io.yupiik.asciidoc.model.Anchor;
import io.yupiik.asciidoc.model.Attribute;
import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.CallOut;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.DescriptionList;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.FloatingTitle;
import io.yupiik.asciidoc.model.Header;
import io.yupiik.asciidoc.model.HorizontalRule;
import io.yupiik.asciidoc.model.LineBreak;
import io.yupiik.asciidoc.model.Link;
import io.yupiik.asciidoc.model.Listing;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.OpenBlock;
import io.yupiik.asciidoc.model.OrderedList;
import io.yupiik.asciidoc.model.PageBreak;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.PassthroughBlock;
import io.yupiik.asciidoc.model.Quote;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Table;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.model.UnOrderedList;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.internal.LocalContextResolver;
import io.yupiik.asciidoc.parser.internal.Reader;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.Visitor;
import io.yupiik.asciidoc.renderer.a2s.YupiikA2s;
import io.yupiik.asciidoc.renderer.uri.DataResolver;
import io.yupiik.asciidoc.renderer.uri.DataUri;
import lombok.Getter;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.yupiik.asciidoc.model.Element.ElementType.ANCHOR;
import static io.yupiik.asciidoc.model.Element.ElementType.ATTRIBUTE;
import static io.yupiik.asciidoc.model.Element.ElementType.LINK;
import static io.yupiik.asciidoc.model.Element.ElementType.ORDERED_LIST;
import static io.yupiik.asciidoc.model.Element.ElementType.PARAGRAPH;
import static io.yupiik.asciidoc.model.Element.ElementType.SECTION;
import static io.yupiik.asciidoc.model.Element.ElementType.TEXT;
import static io.yupiik.asciidoc.model.Element.ElementType.UNORDERED_LIST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;
import static java.util.Map.entry;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

/**
 * Important: as of today it is a highly incomplete implementation but it gives a starting point.
 * <p>
 * Trivial document renderer as HTML.
 */
public class AsciidoctorLikeHtmlRenderer implements Visitor<String> {
    protected final StringBuilder builder = new StringBuilder();
    protected final Configuration configuration;
    protected final boolean dataUri;
    protected final DataResolver resolver;
    protected final State state = new State(); // this is why we are not thread safe
    protected final Parser subParser;
    protected final ContentResolver subResolver;
    protected boolean usesMermaid;

    public AsciidoctorLikeHtmlRenderer() {
        this(new Configuration().setAttributes(Map.of()));
    }

    public AsciidoctorLikeHtmlRenderer(final Configuration configuration) {
        this.configuration = configuration;

        final var dataUriValue = configuration.getAttributes().getOrDefault("data-uri", "false");
        this.dataUri = Boolean.parseBoolean(dataUriValue) || dataUriValue.isBlank();
        this.resolver = dataUri ?
                (configuration.getResolver() == null ? new DataResolver(assetsDir(configuration, "imagesdir")) : configuration.getResolver()) :
                null;
        this.subParser = new Parser(configuration.getAttributes() == null ? Map.of() : configuration.getAttributes());
        this.subResolver = new LocalContextResolver(configuration.getAssetsBase());
    }

    private Path assetsDir(final Configuration configuration, final String attribute) {
        final var assetsBase = configuration.getAssetsBase();
        final var attrValue = configuration.getAttributes().get(attribute);
        if (attrValue != null && !attrValue.isBlank()) {
            return assetsBase.resolve(attrValue);
        }
        return assetsBase;
    }

    @Override
    public void visitBody(final Body body) {
        final var noheader = Boolean.parseBoolean(configuration.getAttributes().getOrDefault("noheader", "false"));
        final var tocAttr = attr("toc", "toc", "none", state.document.header().attributes());
        final var showToc = !"none".equals(tocAttr);
        final var headerAttrs = state.document.header().attributes();
        final var maxWidthAttr = attr("max-width", headerAttrs);
        final var maxWidthStyle = maxWidthAttr != null ? " style=\"max-width: " + maxWidthAttr + ";\"" : "";

        if (!noheader) {
            if (showToc) {
                visitToc(body);
            }
            if (!configuration.isSkipGlobalContentWrapper()) {
                builder.append(" </div>\n");
                builder.append(" <div id=\"content\"").append(maxWidthStyle).append(">\n");
            }
        } else {
            if (showToc) {
                visitToc(body);
            }
        }

        state.footnoteIndex = 0;
        state.footnotes.clear();
        state.stackChain(body.children(), () -> Visitor.super.visitBody(body));

        if (!noheader && !configuration.isSkipGlobalContentWrapper()) {
            builder.append(" </div>\n");
        }

        if (!state.footnotes.isEmpty()) {
            builder.append(" <div id=\"footnotes\"").append(maxWidthStyle).append(">\n");
            builder.append("  <hr").append(voidSlash()).append(">\n");
            for (final var fn : state.footnotes) {
                builder.append("  <div class=\"footnote\" id=\"_footnotedef_").append(fn.index).append("\">\n");
                builder.append("   <a href=\"#_footnoteref_").append(fn.index).append("\">").append(fn.index).append("</a>");
                builder.append(". ").append(escape(fn.text)).append("\n");
                builder.append("  </div>\n");
            }
            builder.append(" </div>\n");
        }
    }

    @Override
    public void visitConditionalBlock(final ConditionalBlock element) {
        final var ctx = context();
        if (element.evaluator().test(ctx)) {
            state.stackChain(element.children(), () -> element.children().forEach(this::visitElement));
        } else {
            for (final var branch : element.elseBranches()) {
                if (branch.evaluator().test(ctx)) {
                    state.stackChain(branch.children(), () -> branch.children().forEach(this::visitElement));
                    return;
                }
            }
        }
    }

    @Override
    public ConditionalBlock.Context context() {
        final var attrs = configuration.getAttributes();
        final var docAttrs = state.document.header().attributes();
        return key -> {
            final var v = docAttrs.get(key);
            if (v != null) return v;
            return attrs.get(key);
        };
    }

    @Override
    public void visitElement(final Element element) {
        state.lastElement.add(element);
        if (!state.sawPreamble && state.lastElement.size() >= 2 && element.type() != TEXT && element.type() != PARAGRAPH) {
            state.sawPreamble = true;
        }
        try {
            Visitor.super.visitElement(element);
        } finally {
            state.lastElement.remove(state.lastElement.size() - 1);
        }
    }

    @Override
    public void visit(final Document document) {
        state.document = document;
        final var embeddedAttr = attr("embedded", document.header().attributes());
        final boolean contentOnly = Boolean.parseBoolean(configuration.getAttributes().getOrDefault("noheader", "false"))
                || "true".equals(embeddedAttr) || "".equals(embeddedAttr);
        if (!contentOnly) {
            final var attributes = document.header().attributes();

            builder.append("<!DOCTYPE html>\n");
            builder.append("<html");
            if (attr("nolang", attributes) == null) {
                final var lang = attr("lang", attributes);
                builder.append(" lang=\"").append(lang == null ? "en" : lang).append('"');
            }
            if ("xml".equals(attr("htmlsyntax", attributes))) {
                builder.append(" xmlns=\"http://www.w3.org/1999/xhtml\"");
            }
            builder.append(">\n");
            builder.append("<head>\n");

            final var encoding = attr("encoding", attributes);
            builder.append(" <meta charset=\"").append(encoding == null ? "UTF-8" : encoding).append('"').append(voidSlash()).append(">\n");
            builder.append(" <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"").append(voidSlash()).append(">\n");
            builder.append(" <meta name=\"generator\" content=\"Asciidoctor ").append(attr("asciidoctor-version", "asciidoctor-version", "", attributes)).append('"').append(voidSlash()).append(">\n");

            final var appName = attr("app-name", attributes);
            if (appName != null) {
                builder.append(" <meta name=\"application-name\" content=\"").append(appName).append('"').append(voidSlash()).append(">\n");
            }
            final var description = attr("description", attributes);
            if (description != null) {
                builder.append(" <meta name=\"description\" content=\"").append(description).append('"').append(voidSlash()).append(">\n");
            }
            final var keywords = attr("keywords", attributes);
            if (keywords != null) {
                builder.append(" <meta name=\"keywords\" content=\"").append(keywords).append('"').append(voidSlash()).append(">\n");
            }
            final var author = attr("author", attributes);
            if (author != null) {
                builder.append(" <meta name=\"author\" content=\"").append(author).append('"').append(voidSlash()).append(">\n");
            }
            final var copyright = attr("copyright", attributes);
            if (copyright != null) {
                builder.append(" <meta name=\"copyright\" content=\"").append(copyright).append('"').append(voidSlash()).append(">\n");
            }

            if (attr("asciidoctor-css", "asciidoctor-css", null, attributes) != null) {
                builder.append(" <link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/asciidoctor.js/1.5.9/css/asciidoctor.min.css\" integrity=\"sha512-lb4ZuGfCVoGO2zu/TMakNlBgRA6mPXZ0RamTYgluFxULAwOoNnBIZaNjsdfhnlKlIbENaQbEAYEWxtzjkB8wsQ==\" crossorigin=\"anonymous\" referrerpolicy=\"no-referrer\"").append(voidSlash()).append(">\n");
            }
            builder.append(attr("custom-css", "custom-css", "", attributes));

            if ("font".equals(attr("icons", "icons", null, attributes))) {
                if (attr("iconfont-remote", "iconfont-remote", null, attributes) != null) {
                    var iconfontCdn = attr("iconfont-cdn", "iconfont-cdn", null, attributes);
                    if (iconfontCdn == null) {
                        iconfontCdn = "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/4.7.0/css/font-awesome.min.css";
                    }
                    builder.append(" <link rel=\"stylesheet\" href=\"").append(iconfontCdn).append('"').append(voidSlash()).append(">\n");
                } else {
                    final var iconfontName = attr("iconfont-name", "iconfont-name", "font-awesome", attributes) + ".css";
                    final var stylesdir = attr("stylesdir", "stylesdir", "", attributes);
                    builder.append(" <link rel=\"stylesheet\" href=\"").append(stylesdir).append(iconfontName).append('"').append(voidSlash()).append(">\n");
                }
            }

            final var favicon = attr("favicon", attributes);
            if (favicon != null) {
                final var iconHref = favicon.isBlank() ? "favicon.ico" : favicon;
                final var iconType = favicon.isBlank() || !iconHref.contains(".") ? "image/x-icon" :
                        iconHref.endsWith(".ico") ? "image/x-icon" :
                                "image/" + iconHref.substring(iconHref.lastIndexOf('.') + 1);
                builder.append(" <link rel=\"icon\" type=\"").append(iconType).append("\" href=\"").append(iconHref).append('"').append(voidSlash()).append(">\n");
            }

            final var docTitle = document.header().title();
            if (docTitle != null && !docTitle.isBlank()) {
                builder.append(" <title>").append(docTitle).append("</title>\n");
            }

            beforeHeadEnd();
            builder.append("</head>\n");

            builder.append("<body");
            final var bodyClass = attr("body-classes", "body-classes", null, attributes);
            final var doctype = attr("doctype", attributes);
            final var defaultBodyClass = doctype == null ? "article" : doctype;
            if (bodyClass != null) {
                builder.append(" class=\"").append(bodyClass).append(" ").append(defaultBodyClass).append("\"");
            } else {
                builder.append(" class=\"").append(defaultBodyClass).append("\"");
            }
            builder.append(">\n");
            builder.append(attr("header-html", "header-html", "", document.header().attributes()));
            afterBodyStart();

            final var maxWidthAttr = attr("max-width", attributes);
            final var maxWidthStyle = maxWidthAttr != null ? " style=\"max-width: " + maxWidthAttr + ";\"" : "";

            if (!configuration.isSkipGlobalContentWrapper()) {
                builder.append(" <div id=\"header\"").append(maxWidthStyle).append(">\n");
            }
        }
        Visitor.super.visit(document);
        if (!contentOnly) {

            if (attr("nofooter", document.header().attributes()) == null) {
                final var footerMaxWidth = attr("max-width", document.header().attributes());
                final var footerMaxWidthStyle = footerMaxWidth != null ? " style=\"max-width: " + footerMaxWidth + ";\"" : "";
                builder.append(" <div id=\"footer\"").append(footerMaxWidthStyle).append(">\n");
                builder.append("  <div id=\"footer-text\">\n");
                final var revNumber = document.header().revision().number();
                if (revNumber != null && !revNumber.isBlank()) {
                    builder.append("   ").append(attr("version-label", "version-label", "Version", document.header().attributes())).append(" ").append(revNumber).append("<br").append(voidSlash()).append(">\n");
                }
                final var reproducible = attr("reproducible", document.header().attributes());
                final var lastUpdateLabel = attr("last-update-label", "last-update-label", "Last updated", document.header().attributes());
                if (lastUpdateLabel != null && reproducible == null) {
                    final var docdatetime = attr("docdatetime", "docdatetime", null, document.header().attributes());
                    if (docdatetime != null) {
                        builder.append("   ").append(escape(lastUpdateLabel)).append(" ").append(escape(docdatetime)).append("<br").append(voidSlash()).append(">\n");
                    }
                }
                builder.append("  </div>\n");
                builder.append(" </div>\n");
            }

            if (state.hasStem && attr("skip-stem-js", document.header().attributes()) == null) {
                builder.append("""
                        <script type="text/x-mathjax-config">
                        MathJax.Hub.Config({
                          messageStyle: "none",
                          tex2jax: { inlineMath: [["\\\\(", "\\\\)"]], displayMath: [["\\\\[", "\\\\]"]], ignoreClass: "nostem|nolatexmath" },
                          asciimath2jax: { delimiters: [["\\\\$", "\\\\$"]], ignoreClass: "nostem|noasciimath" },
                          TeX: { equationNumbers: { autoNumber: "none" } }
                        })
                        MathJax.Hub.Register.StartupHook("AsciiMath Jax Ready", function () {
                          MathJax.InputJax.AsciiMath.postfilterHooks.Add(function (data, node) {
                            if ((node = data.script.parentNode) && (node = node.parentNode) && node.classList.contains("stemblock")) {
                              data.math.root.display = "block"
                            }
                            return data
                          })
                        })
                        </script>
                        <script src="//cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.9/MathJax.js?config=TeX-MML-AM_HTMLorMML"></script>
                        """);
            }

            Stream.of(attr("custom-js", "custom-js", "", document.header().attributes()).split(","))
                    .map(String::strip)
                    .filter(Predicate.not(String::isBlank))
                    .map(i -> " " + i + '\n')
                    .forEach(builder::append);
            beforeBodyEnd();
            builder.append("</body>\n");
            builder.append("</html>\n");
        }
    }

    @Override
    public void visitAdmonition(final Admonition element) {
        final var opts = element.options();
        final var classes = "admonitionblock " + element.level().name().toLowerCase(ROOT) +
                (opts.containsKey("role") ? ' ' + opts.get("role") : "");
        builder.append(" <div");
        if (opts.containsKey("id")) {
            builder.append(" id=\"").append(opts.get("id")).append('"');
        }
        builder.append(" class=\"").append(classes).append("\">\n");
        builder.append("""
                          <table>
                            <tbody>
                             <tr>
                              <td class="icon">
                        """);
        final var docAttrs = state.document == null ? Map.<String, String>of() : state.document.header().attributes();
        final var icons = attr("icons", docAttrs);
        if (icons != null) {
            final var label = element.level().name();
            if ("font".equals(icons)) {
                builder.append("     <i class=\"fa icon-").append(label.toLowerCase(ROOT)).append("\" title=\"").append(label).append("\"></i>\n");
            } else {
                builder.append("     <img src=\"").append(icons).append("/").append(label.toLowerCase(ROOT)).append(".png\" alt=\"").append(label).append('"').append(voidSlash()).append(">\n");
            }
        } else {
            builder.append("     <div class=\"title\">").append(element.level().name()).append("</div>\n");
        }
        builder.append("       </td>\n")
                .append("      <td class=\"content\">\n");
        visitElement(element.content());
        builder.append("    </td>\n")
                .append("   </tr>\n")
                .append("      </tbody>\n")
                .append("  </table>\n")
                .append(" </div>\n");
    }

    @Override
    public void visitParagraph(final Paragraph element) {
        state.stackChain(element.children(), () -> {
            final boolean preambleWasHandled = false;
            final boolean nowrap = Boolean.parseBoolean(element.options().getOrDefault("nowrap", "false"));
            handlePreamble(!nowrap, element, () -> {
                if (!state.nowrap && !nowrap) {
                    builder.append(" <div");
                    writeCommonAttributes(element.options(), c -> "paragraph" + (c != null ? ' ' + c : ""));
                    builder.append(">\n");
                }

                final boolean addP = !state.nowrap && !nowrap && !preambleWasHandled && state.sawPreamble && element.children().stream()
                        .allMatch(e -> e.type() == TEXT ||
                                e.type() == ATTRIBUTE ||
                                e.type() == LINK ||
                                e.type() == ANCHOR ||
                                (e instanceof Macro m && m.inline()) ||
                                (e instanceof Code c && c.inline()));
                if (addP) {
                    builder.append(" <p>");
                }
                Visitor.super.visitParagraph(element);
                if (addP) {
                    builder.append("</p>\n");
                }

                if (!state.nowrap && !nowrap) {
                    builder.append(" </div>\n");
                }
            });
        });
    }

    @Override
    public void visitHeader(final Header header) {
        var showTitle = Boolean.parseBoolean(attr("showtitle", "false"))
                        && attr("notitle", (String) null) == null;
        var noHeader = Boolean.parseBoolean(attr("noheader", "false"));

        // showTitle has priority over noHeader (noTitle and showTitle are supposed to be mutually exclusive)
        if ((showTitle || !noHeader) && !header.title().isBlank()) {
            builder.append(" <h1>").append(escape(header.title())).append("</h1>\n");
        }

        final var details = new StringBuilder();
        {
            int authorIdx = 1;
            for (final var a : header.author()) {
                if (!a.name().isBlank()) {
                    details.append("<span class=\"author author-").append(authorIdx).append("\">").append(escape(a.name())).append("</span>\n");
                }
                if (!a.mail().isBlank()) {
                    details.append("<span class=\"email email-").append(authorIdx).append("\">").append(escape(a.mail())).append("</span>\n");
                }
                authorIdx++;
            }
        }
        if (!header.revision().number().isBlank()) {
            details.append("<span id=\"revnumber\">").append(escape(header.revision().number())).append("</span>\n");
        }
        if (!header.revision().date().isBlank()) {
            details.append("<span id=\"revdate\">").append(escape(header.revision().date())).append("</span>\n");
        }
        if (!header.revision().revmark().isBlank()) {
            details.append("<span id=\"revremark\">").append(escape(header.revision().revmark())).append("</span>\n");
        }
        if ("manpage".equals(attr("doctype", header.attributes()))) {
            details.append("<span class=\"manname\">").append(escape(attr("manname", header.attributes()))).append("</span>\n");
            details.append("<span class=\"mansection\">").append(escape(attr("mansection", header.attributes()))).append("</span>\n");
        }
        if (!details.isEmpty()) {
            builder.append("  <div class=\"details\">\n").append(details.toString().indent(3)).append("  </div>\n");
        }
    }

    @Override
    public void visitSection(final Section element) {
        state.stackChain(element.children(), () -> {
            final var titleRenderer = new AsciidoctorLikeHtmlRenderer(configuration);
            titleRenderer.state.sawPreamble = true;
            titleRenderer.state.nowrap = true;
            titleRenderer.visitElement(element.title());
            var title = titleRenderer.result();

            final var docAttrs = state.document.header().attributes();
            final var sectnums = docAttrs.get("sectnums");
            if (sectnums != null) {
                final int level = element.level();
                state.sectionNumberCounters.merge(level, 1, Integer::sum);
                for (int l = level + 1; l <= 6; l++) {
                    state.sectionNumberCounters.remove(l);
                }
                final int numLevels = Integer.parseInt(attr("sectnumlevels", "sectnumlevels", "3", docAttrs));
                final var num = new StringBuilder();
                for (int l = 2; l <= level && l - 1 <= numLevels; l++) {
                    final Integer n = state.sectionNumberCounters.get(l);
                    if (n != null) {
                        if (!num.isEmpty()) num.append(".");
                        num.append(n);
                    }
                }
                if (!num.isEmpty()) {
                    title = num + ". " + title;
                }
            }

            final var sectionStyle = element.options().get("");
            builder.append(" <").append(configuration.getSectionTag());
            writeCommonAttributes(element.options(), c -> {
                var cls = "sect" + (element.level() - 1);
                if (sectionStyle != null && !sectionStyle.isBlank()) {
                    cls += " " + sectionStyle;
                }
                return c == null ? cls : cls + " " + c;
            });
            final var id = element.options().get("id");
            if (id == null) {
                final var prefix = docAttrs.getOrDefault("idprefix", configuration.getAttributes().get("idprefix"));
                final var separator = docAttrs.getOrDefault("idseparator", configuration.getAttributes().get("idseparator"));
                final var generatedId = IdGenerator.forTitle(title, prefix, separator);
                builder.append(" id=\"").append(generatedId).append('"');
                final var sectlinks = docAttrs.get("sectlinks");
                final var sectanchors = docAttrs.get("sectanchors");
                if (sectanchors != null) {
                    final var anchor = "<a class=\"anchor\" href=\"#" + generatedId + "\"></a>";
                    if ("after".equals(sectanchors)) {
                        title = title + anchor;
                    } else {
                        title = anchor + title;
                    }
                }
                if (sectlinks != null) {
                    title = "<a class=\"link\" href=\"#" + generatedId + "\">" + title + "</a>";
                }
            }
            builder.append(">\n");
            builder.append("  <h").append(element.level()).append(">");
            builder.append(title);
            builder.append("</h").append(element.level()).append(">\n");
            if (!configuration.isSkipSectionBody()) {
                builder.append(" <div class=\"sectionbody\">\n");
            }
            Visitor.super.visitSection(element);
            if (!configuration.isSkipSectionBody()) {
                builder.append(" </div>\n");
            }
            builder.append(" </").append(configuration.getSectionTag()).append(">\n");
        });
    }

    @Override
    public void visitFloatingTitle(final FloatingTitle element) {
        final var titleRenderer = new AsciidoctorLikeHtmlRenderer(configuration);
        titleRenderer.state.sawPreamble = true;
        titleRenderer.state.nowrap = true;
        titleRenderer.visitElement(element.title());
        final var title = titleRenderer.result();

        final var style = element.options().getOrDefault("", "discrete");
        builder.append(" <h").append(element.level()).append("");
        writeCommonAttributes(element.options(),
                c -> style + (c == null ? "" : (' ' + c)));
        builder.append(">");
        builder.append(title);
        builder.append("</h").append(element.level()).append(">\n");
    }

    @Override
    public void visitHorizontalRule(final HorizontalRule element) {
        builder.append(" <hr");
        writeCommonAttributes(element.options(), null);
        builder.append(voidSlash()).append(">\n");
    }

    @Override
    public void visitLineBreak(final LineBreak element) {
        builder.append("<br").append(voidSlash()).append(">\n");
    }

    @Override
    public void visitPageBreak(final PageBreak element) {
        builder.append(" <div class=\"page-break\"></div>\n");
    }

    @Override
    public void visitLink(final Link element) {
        final boolean parentNeedsP = state.lastElement.size() > 1 && isList(state.lastElement.get(state.lastElement.size() - 2).type());
        if (parentNeedsP) { // really to mimic asciidoctor
            builder.append(" <p>");
        }

        final boolean code = element.options().getOrDefault("role", "").contains("inline-code");
        if (code) {
            builder.append("<code>");
        }

        builder.append(" <a href=\"").append(element.url()).append("\"");
        writeCommonAttributes(element.options(), null);

        var noFollow = false;
        var noOpener = false;
        final var window = element.options().get("window");
        if (window != null) {
            if ("_blank".equals(window)) {
                noOpener = true;
            }
            builder.append(" target=\"").append(window).append("\"");
        }

        final var optsAttribute = element.options().get("opts");
        if (optsAttribute != null) {
            for (var optValue : optsAttribute.split(",")) {
                switch (optValue) {
                    case "nofollow" -> noFollow = true;
                    case "noopener" -> noOpener = true;
                }
            }
        }

        if (noFollow || noOpener) {

            builder.append(" rel=\"");

            if (noFollow) {
                builder.append("nofollow");
            }
            if (noOpener) {
                if (noFollow) {
                    builder.append(" ");
                }
                builder.append("noopener");
            }

            builder.append("\"");
        }

        builder.append(">");

        if (state.visitingWrapperLink) {
            builder.append("\n ");
        }

        var label = element.label();
        if (label instanceof Text t && attr("hide-uri-scheme", element.options()) != null) {
            if (t.value().contains("://")) {
                label = new Text(t.style(), t.value().substring(t.value().indexOf("://") + "://".length()), t.options());
            } else if (t.value().contains(":")) { // mailto for ex
                label = new Text(t.style(), t.value().substring(t.value().indexOf(":") + 1), t.options());
            }
        }
        if (label instanceof Text t && t.style().isEmpty() && (t.options().isEmpty() || Map.of("nowrap", "true").equals(t.options()))) {
            builder.append(escape(t.value()));
        } else {
            visitElement(label);
        }
        builder.append("</a>\n");

        if (code) {
            builder.append("</code>");
        }
        if (parentNeedsP) {
            builder.append("</p>");
        }
    }

    @Override
    public void visitDescriptionList(final DescriptionList element) {
        if (element.children().isEmpty()) {
            return;
        }
        final var style = element.options().get("");
        if ("qanda".equals(style)) {
            renderQAndADescriptionList(element);
        } else if ("horizontal".equals(style)) {
            renderHorizontalDescriptionList(element);
        } else {
            renderDefaultDescriptionList(element);
        }
    }

    private void renderDefaultDescriptionList(final DescriptionList element) {
        state.stackChain(new ArrayList<>(element.children().values()), () -> {
            final var style = element.options().get("");
            final var role = element.options().get("role");
            final var classes = "dlist" + (style != null ? " " + style : "") + (role != null ? " " + role : "");
            builder.append(" <div class=\"").append(classes).append("\">\n");
            writeBlockTitle(element.options());
            builder.append("  <dl");
            writeCommonAttributes(element.options(), null);
            builder.append(">\n");
            for (final var elt : element.children().entrySet()) {
                builder.append("    <dt");
                if (style == null) {
                    builder.append(" class=\"hdlist1\"");
                }
                builder.append(">");
                visitElement(elt.getKey());
                builder.append("</dt>\n");
                builder.append("    <dd>\n");
                final var ddValue = elt.getValue();
                if (ddValue instanceof Text || ddValue instanceof Code || ddValue instanceof Link || ddValue instanceof Macro || ddValue instanceof Quote) {
                    builder.append("<p>");
                    visitElement(ddValue);
                    builder.append("</p>\n");
                } else {
                    visitElement(ddValue);
                }
                builder.append("</dd>\n");
            }
            builder.append("  </dl>\n");
            builder.append(" </div>\n");
        });
    }

    private void renderQAndADescriptionList(final DescriptionList element) {
        state.stackChain(new ArrayList<>(element.children().values()), () -> {
            final var role = element.options().get("role");
            final var classes = "qlist qanda" + (role != null ? ' ' + role : "");
            builder.append(" <div class=\"").append(classes).append("\">\n");
            writeBlockTitle(element.options());
            builder.append("  <ol>\n");
            for (final var elt : element.children().entrySet()) {
                builder.append("   <li>\n");
                builder.append("    <p><em>");
                visitElement(elt.getKey());
                builder.append("</em></p>\n");
                builder.append("    <p>");
                visitElement(elt.getValue());
                builder.append("</p>\n");
                builder.append("   </li>\n");
            }
            builder.append("  </ol>\n");
            builder.append(" </div>\n");
        });
    }

    private void renderHorizontalDescriptionList(final DescriptionList element) {
        state.stackChain(new ArrayList<>(element.children().values()), () -> {
            final var role = element.options().get("role");
            final var classes = "hdlist" + (role != null ? ' ' + role : "");
            builder.append(" <div class=\"").append(classes).append("\">\n");
            writeBlockTitle(element.options());
            builder.append("  <table>\n");
            for (final var elt : element.children().entrySet()) {
                builder.append("   <tr>\n");
                final var strongOption = element.options().containsKey("strong");
                builder.append("    <td class=\"hdlist1").append(strongOption ? " strong" : "").append("\">");
                visitElement(elt.getKey());
                builder.append("</td>\n");
                builder.append("    <td class=\"hdlist2\">\n");
                visitElement(elt.getValue());
                builder.append("    </td>\n");
                builder.append("   </tr>\n");
            }
            builder.append("  </table>\n");
            builder.append(" </div>\n");
        });
    }

    @Override
    public void visitUnOrderedList(final UnOrderedList element) {
        if (element.children().isEmpty()) {
            return;
        }
        final var isChecklist = element.options().containsKey("checklist");
        state.stackChain(element.children(), () -> {
            builder.append(" <div");
            writeCommonAttributes(element.options(), c ->
                    isChecklist ? "ulist checklist" + (c != null ? ' ' + c : "") : "ulist" + (c != null ? ' ' + c : ""));
            builder.append(">\n");
            builder.append(isChecklist ? " <ul class=\"checklist\">\n" : " <ul>\n");
            visitListElements(element.children(), element.options());
            builder.append(" </ul>\n");
            builder.append(" </div>\n");
        });
    }


    @Override
    public void visitOrderedList(final OrderedList element) {
        if (element.children().isEmpty()) {
            return;
        }
        final var style = element.options().get("style");
        final var start = element.options().get("start");
        state.stackChain(element.children(), () -> {
            builder.append(" <div");
            writeCommonAttributes(element.options(), c -> {
                final var base = "olist" + (c != null ? ' ' + c : "");
                return style != null ? "olist " + style + (c != null ? " " + c : "") : base;
            });
            builder.append(">\n");
            builder.append(" <ol");
            if (style != null) {
                final var role = element.options().get("role");
                builder.append(" class=\"").append(style);
                if (role != null) {
                    builder.append(" ").append(role.replace('.', ' '));
                }
                builder.append("\"");
                final var type = switch (style) {
                    case "loweralpha" -> "a";
                    case "upperalpha" -> "A";
                    case "lowerroman" -> "i";
                    case "upperroman" -> "I";
                    default -> null;
                };
                if (type != null) {
                    builder.append(" type=\"").append(type).append("\"");
                }
            } else {
                writeCommonAttributes(element.options(), null);
            }
            if (start != null) {
                builder.append(" start=\"").append(start).append("\"");
            }
            builder.append(">\n");
            visitListElements(element.children(), Map.of());
            builder.append(" </ol>\n");
            builder.append(" </div>\n");
        });
    }

    private void visitListElements(final List<Element> element, final Map<String, String> listOptions) {
        final var isChecklist = listOptions.containsKey("checklist");
        final var isInteractive = listOptions.containsKey("interactive");
        for (final var elt : element) {
            builder.append("  <li>\n");
            if (elt instanceof Paragraph p && isChecklist && "true".equals(p.options().get("checkbox"))) {
                final var checked = "true".equals(p.options().get("checked"));
                builder.append("   <p>");
                if (isInteractive) {
                    builder.append(checked ?
                            "<input type=\"checkbox\" data-item-complete=\"1\" checked> " :
                            "<input type=\"checkbox\" data-item-complete=\"0\"> ");
                } else {
                    builder.append(checked ? "&#10003; " : "&#10063; ");
                }
                for (final var child : p.children()) {
                    if (child instanceof Text t) {
                        final var opts = new HashMap<>(t.options());
                        opts.put("nowrap", "true");
                        visitElement(new Text(t.style(), t.value(), opts));
                    } else {
                        visitElement(child);
                    }
                }
                builder.append("</p>\n");
            } else {
                visitElement(elt);
            }
            builder.append("  </li>\n");
        }
    }

    @Override
    public void visitText(final Text element) {
        final var useWrappers = element.options().get("nowrap") == null;

        final boolean preambleSaw = state.sawPreamble;
        handlePreamble(useWrappers, element, () -> {
            if (element.options().containsKey("bibliography")) {
                builder.append("<a id=\"").append(escape(element.options().get("id"))).append("\"></a>\n");
                return;
            }
            if (Objects.equals("abstract", element.options().get("role"))) { // we unwrapped the paragraph in some cases so add it back
                if (preambleSaw) {
                    builder.append(" <div class=\"sect1\">\n");
                }
                // not 100% sure of why asciidoctor does it sometimes (open blocks) but trying to behave the same to keep existing theme
                visitQuote(new Quote(List.of(new Text(element.style(), element.value(), Map.of())), Map.of("role", "quoteblock abstract")));
                if (preambleSaw) {
                    builder.append(" </div>\n");
                }
                return;
            }

            final boolean isParagraph = !state.nowrap && !state.inCallOut && useWrappers &&
                    (state.lastElement.size() <= 1 || state.lastElement.get(state.lastElement.size() - 2).type() == SECTION);
            if (isParagraph) {
                // not writeCommonAttributes to not add twice the id for ex
                final var customRole = element.options().get("role");
                builder.append(" <div class=\"paragraph");
                if (customRole != null) {
                    builder.append(' ').append(customRole);
                }
                builder.append("\">\n");
            }

            final boolean parentNeedsP = state.lastElement.size() > 1 && isList(state.lastElement.get(state.lastElement.size() - 2).type());
            final boolean wrap = useWrappers &&
                    (parentNeedsP || (element.style().size() != 1 && (isParagraph || state.inCallOut || !element.options().isEmpty())));
            final boolean useP = parentNeedsP || isParagraph || !state.inCallOut;
            if (wrap) {
                builder.append(" <").append(useP ? "p" : "span");
                writeCommonAttributes(element.options(), null);
                builder.append(">\n");
            }
                    final var styleTags = element.style().stream()
                            .map(s -> switch (s) {
                                case BOLD -> "strong";
                                case ITALIC -> "em";
                                case EMPHASIS -> "em";
                                case SUB -> "sub";
                                case SUP -> "sup";
                                case MARK -> "mark";
                                case STRIKETHROUGH -> "del";
                            })
                            .toList();
            if (!styleTags.isEmpty()) {
                builder.append('<').append(styleTags.get(0));
                if (!wrap) {
                    writeCommonAttributes(element.options(), null);
                }
                builder.append('>');
                if (styleTags.size() > 1) {
                    builder.append(styleTags.stream().skip(1).map(s -> '<' + s + '>').collect(joining()));
                }
            }
            builder.append(escape(element.value()));
            builder.append(styleTags.stream().sorted(Comparator.reverseOrder()).map(s -> "</" + s + '>').collect(joining()));
            if (wrap) {
                builder.append("\n </").append(useP ? "p" : "span").append(">\n");
            }

            if (isParagraph) {
                builder.append(" </div>\n");
            }
        });
    }

    @Override
    public void visitCode(final Code element) {
        if (element.inline()) {
            builder.append("<code>").append(escape(element.value())).append("</code>");
            return;
        }

        final var carbonNowBaseUrl = element.options().get("carbonNowBaseUrl");
        if (carbonNowBaseUrl != null) { // consider the code block as an image
            final var frame = "  <iframe\n" +
                    "    src=\"" + (carbonNowBaseUrl.isBlank() || "auto".equals(carbonNowBaseUrl) ?
                    // todo: this needs to be tuned/tunable
                    "https://carbon.now.sh/embed?bg=rgba%28171%2C184%2C195%2C100%29&t=vscode&wt=none&l=text%2Fx-java&width=680&ds=true&dsyoff=20px&dsblur=68px&wc=true&wa=true&pv=48px&ph=32px&ln=true&fl=1&fm=Droid+Sans+Mono&fs=13px&lh=133%25&si=false&es=2x&wm=false&code=" :
                    carbonNowBaseUrl) + URLEncoder.encode(element.value(), UTF_8) + "\"\n" +
                    "    style=\"width: 1024px; height: 473px; border:0; transform: scale(1); overflow:hidden;\"\n" +
                    "    sandbox=\"allow-scripts allow-same-origin\">\n" +
                    "  </iframe>";
            visitPassthroughBlock(new PassthroughBlock(frame, element.options()));
            return;
        }

        final var lang = element.options().getOrDefault("lang", element.options().get("language"));
        final var style = element.options().get("");

        final var isListing = lang != null || "source".equals(style) || "listing".equals(style);
        if (isListing) {
            builder.append(" <div class=\"listingblock\">\n <div class=\"content\">\n");
            final var linenums = element.options().containsKey("linenums-option");
            builder.append(" <pre class=\"highlightjs highlight").append(linenums ? " linenums" : "").append("\">");
            builder.append("<code");
            writeCommonAttributes(element.options(), c -> (lang != null ? "language-" + lang + (c != null ? ' ' + c : "") : c) + " hljs");
            if (lang != null) {
                builder.append(" data-lang=\"").append(lang).append("\"");
            }
            if (linenums) {
                builder.append(" data-linenums=\"true\"");
            }
            builder.append(">");
            var html = escape(element.value());
            if (linenums) {
                final var lines = html.split("\n");
                final var numbered = new StringBuilder();
                for (int i = 0; i < lines.length; i++) {
                    numbered.append("<span class=\"linenums\">").append(i + 1).append("</span>").append(lines[i]).append('\n');
                }
                html = numbered.toString();
            }
            builder.append("false".equalsIgnoreCase(element.options().get("hightlight-callouts")) ? html : highlightCallOuts(element.callOuts(), html));
            builder.append("</code></pre>\n </div>\n </div>\n");
        } else {
            final var nowrap = element.options().containsKey("nowrap-option") || state.nowrap;
            builder.append(" <div class=\"literalblock\">\n <div class=\"content\">\n");
            builder.append(" <pre").append(nowrap ? " class=\"nowrap\"" : "").append(">");
            builder.append(escape(element.value()));
            builder.append("</pre>\n </div>\n </div>\n");
        }

        if (!element.callOuts().isEmpty()) {
            builder.append(" <div class=\"colist arabic\">\n");
            builder.append("  <ol>\n");
            element.callOuts().forEach(c -> {
                final boolean nowrap = state.nowrap;
                builder.append("   <li>\n");
                state.inCallOut = true;
                state.nowrap = true;
                if (c.text() instanceof Paragraph p && p.options().isEmpty()) {
                    p.children().forEach(this::visitElement);
                } else {
                    visitElement(c.text());
                }
                state.inCallOut = false;
                state.nowrap = nowrap;
                builder.append("   </li>\n");
            });
            builder.append("  </ol>\n");
            builder.append(" </div>\n");
        }
    }

    @Override
    public void visitTable(final Table element) {
        final var autowidth = element.options().containsKey("autowidth");
        final var frame = "ends".equals(attr("frame", "table-frame", "all", element.options())) ? "ends"
                : "topbot".equals(attr("frame", "table-frame", "all", element.options())) ? "ends"
                : attr("frame", "table-frame", "all", element.options());
        final var pcWidth = element.options().get("tablepcwidth");
        final var tableClasses = new StringBuilder("tableblock");
        tableClasses.append(" frame-").append(frame);
        tableClasses.append(" grid-").append(attr("grid", "table-grid", "all", element.options()));
        final var stripes = attr("stripes", "table-stripes", null, element.options());
        if (stripes != null) {
            tableClasses.append(" stripes-").append(stripes);
        }
        if (autowidth && !element.options().containsKey("width")) {
            tableClasses.append(" fit-content");
        } else if (pcWidth == null || "100".equals(pcWidth)) {
            tableClasses.append(" stretch");
        }
        if (element.options().containsKey("float")) {
            tableClasses.append(" float");
        }

        final var tableStyleAttr = pcWidth != null && !"100".equals(pcWidth) ? " style=\"width: " + pcWidth + "%;\"" : "";
        final var classes = tableClasses.toString();

        builder.append(" <table");
        writeCommonAttributes(element.options(), c -> classes + (c == null ? "" : (' ' + c)));
        builder.append(tableStyleAttr);
        builder.append(">\n");

        final var title = element.options().get("title");
        if (title != null) {
            builder.append("  <caption class=\"title\">").append(escape(title)).append("</caption>\n");
        }

        if (!element.elements().isEmpty()) { // has row(s)
            final var firstRow = element.elements().get(0);
            final var cols = ofNullable(element.options().get("cols"))
                    .map(it -> Stream.of(it.split(",")).map(this::extractNumbers).toList())
                    .orElse(List.of());
            final var haligns = ofNullable(element.options().get("cols"))
                    .map(it -> Stream.of(it.split(",")).map(String::strip).map(this::extractAlignment).toList())
                    .orElse(List.of());

            builder.append("  <colgroup>\n");
            if (autowidth) {
                IntStream.range(0, firstRow.size()).forEach(i -> builder.append("   <col>\n"));
            } else {
                final int totalWeight = cols.stream().mapToInt(Integer::intValue).sum();
                final int pc = (int) (100. / Math.max(1, totalWeight));
                IntStream.range(0, cols.size()).forEach(i -> builder.append("   <col style=\"width: ").append(cols.get(i) * pc).append("%;\">\n"));
            }
            builder.append("  </colgroup>\n");

            if (!element.options().containsKey("noheader-option")) {
                builder.append("  <thead>\n");
                builder.append("   <tr>\n");
                int colIdx = 0;
                for (final var it : firstRow) {
                    final var halign = colIdx < haligns.size() ? haligns.get(colIdx) : "left";
                    writeTableCell("th", it, halign, "halign-" + halign);
                    colIdx++;
                }
                builder.append("   </tr>\n");
                builder.append("  </thead>\n");
            }

            if (element.options().containsKey("noheader-option") || element.elements().size() > 1) {
                final var startRow = element.options().containsKey("noheader-option") ? 0 : 1;
                builder.append("  <tbody>\n");
                element.elements().stream().skip(startRow).forEach(row -> {
                    builder.append("   <tr>\n");
                    int colIdx = 0;
                    for (final var col : row) {
                        final var halign = colIdx < haligns.size() ? haligns.get(colIdx) : "left";
                        writeTableCell("td", col, halign, "halign-" + halign);
                        colIdx++;
                    }
                    builder.append("   </tr>\n");
                });
                builder.append("  </tbody>\n");
            }
        }

        builder.append(" </table>\n");
    }

    @Override
    public void visitQuote(final Quote element) {
        final var opts = element.options();
        final var isVerseblock = "verseblock".equals(opts.get("role")) || "verse".equals(opts.get(""));

        builder.append(" <div");
        writeCommonAttributes(opts, null);
        builder.append(">\n");

        writeBlockTitle(opts);

        final var attribution = opts.get("attribution");
        final var citetitle = opts.get("citetitle");
        if (isVerseblock) {
            builder.append("  <pre class=\"content\">\n");
            Visitor.super.visitQuote(element);
            builder.append("  </pre>\n");
        } else {
            builder.append("  <blockquote>\n");
            Visitor.super.visitQuote(element);
            builder.append("  </blockquote>\n");
        }

        if (attribution != null) {
            builder.append("  <div class=\"attribution\">\n");
            builder.append("&#8212; ").append(escape(attribution));
            if (citetitle != null) {
                builder.append("<br").append(voidSlash()).append(">\n").append("<cite>").append(escape(citetitle)).append("</cite>");
            }
            builder.append("\n  </div>\n");
        }

        builder.append(" </div>");
    }

    @Override
    public void visitAnchor(final Anchor element) {
        final var id = element.value();
        var text = element.label();
        if (text == null || text.isBlank()) {
            ensureXrefCatalog();
            final var resolved = state.xrefCatalog != null ? state.xrefCatalog.get(id) : null;
            text = resolved != null ? resolved : id;
        }
        visitLink(new Link("#" + id, new Text(List.of(), text, Map.of()), Map.of()));
    }

    @Override
    public void visitPassthroughBlock(final PassthroughBlock element) {
        switch (element.options().getOrDefault("", "")) {
            case "stem", "latexmath", "asciimath" -> visitStem(new Macro("stem", element.value(), element.options(), false));
            default -> builder.append("\n").append(element.value()).append("\n");
        }
    }

    @Override
    public void visitOpenBlock(final OpenBlock element) {
        switch (element.options().getOrDefault("", "")) {
            case "NOTE" -> visitAsAdmonition(Admonition.Level.NOTE, element);
            case "TIP" -> visitAsAdmonition(Admonition.Level.TIP, element);
            case "IMPORTANT" -> visitAsAdmonition(Admonition.Level.IMPORTANT, element);
            case "WARNING" -> visitAsAdmonition(Admonition.Level.WARNING, element);
            case "CAUTION" -> visitAsAdmonition(Admonition.Level.CAUTION, element);
            default -> state.stackChain(element.children(), () -> {
                boolean collapsibleHandled = false;
                boolean skipDiv = false;
                boolean innerContent = false;
                if (element.options().get("abstract") != null) {
                    builder.append(" <div");
                    writeCommonAttributes(element.options(), c -> "quoteblock abstract" + (c == null ? "" : (' ' + c)));
                    builder.append(">\n");
                } else if ("sidebar".equals(element.options().get(""))) {
                    builder.append(" <div");
                    writeCommonAttributes(element.options(), c -> "sidebarblock" + (c == null ? "" : (' ' + c)));
                    builder.append(">\n");
                    innerContent = true;
                } else if ("example".equals(element.options().get(""))) {
                    final var collapsible = element.options().containsKey("collapsible-option") || "collapsible".equals(element.options().get("opts"));
                    if (collapsible) {
                        final var open = element.options().containsKey("open-option") || "open".equals(element.options().get("opts"));
                        builder.append(" <details");
                        writeCommonAttributes(element.options(), null);
                        if (open) {
                            builder.append(" open");
                        }
                        builder.append(">\n");
                        final var title = element.options().get("title");
                        builder.append("  <summary class=\"title\">").append(title != null ? escape(title) : "Details").append("</summary>\n");
                        builder.append("  <div class=\"content\">\n");
                        Visitor.super.visitOpenBlock(element);
                        builder.append("  </div>\n");
                        builder.append(" </details>\n");
                        collapsibleHandled = true;
                    } else {
                        builder.append(" <div");
                        writeCommonAttributes(element.options(), c -> "exampleblock" + (c == null ? "" : (' ' + c)));
                        builder.append(">\n");
                        innerContent = true;
                    }
                } else if ("listing".equals(element.options().get(""))) {
                    builder.append(" <div");
                    writeCommonAttributes(element.options(), c -> "listingblock" + (c == null ? "" : (' ' + c)));
                    builder.append(">\n");
                    innerContent = true;
                } else {
                    builder.append(" <div");
                    writeCommonAttributes(element.options(), c -> "openblock" + (c == null ? "" : (' ' + c)));
                    builder.append(">\n");
                    innerContent = true;
                }
                if (!collapsibleHandled) {
                    writeBlockTitle(element.options());
                    builder.append("  <div");
                    if (innerContent) {
                        writeCommonAttributes(element.options(), c -> "content" + (c == null ? "" : (' ' + c)));
                    }
                    builder.append(">\n");
                    Visitor.super.visitOpenBlock(element);
                    builder.append("  </div>\n");
                    if (!skipDiv) {
                        builder.append(" </div>\n");
                    }
                }
            });
        }
    }

    @Override
    public void visitAttribute(final Attribute element) {
        final var name = element.attribute();
        if (name.startsWith("counter:")) {
            final var afterPrefix = name.substring("counter:".length());
            final var colon = afterPrefix.indexOf(':');
            final var counterName = (colon > 0 ? afterPrefix.substring(0, colon) : afterPrefix).strip();
            final var startValue = colon > 0 ? Integer.parseInt(afterPrefix.substring(colon + 1).strip()) : 1;
            final var value = state.counters.merge(counterName, startValue, (old, v) -> old + 1);
            builder.append(value);
            return;
        }
        if (name.startsWith("counter2:")) {
            final var afterPrefix = name.substring("counter2:".length());
            final var colon = afterPrefix.indexOf(':');
            final var counterName = (colon > 0 ? afterPrefix.substring(0, colon) : afterPrefix).strip();
            final var startValue = colon > 0 ? Integer.parseInt(afterPrefix.substring(colon + 1).strip()) : 1;
            final var current = state.counters.getOrDefault(counterName, 0);
            if (!state.counters.containsKey(counterName)) {
                state.counters.put(counterName, startValue);
            } else {
                state.counters.merge(counterName, 1, (old, v) -> old + 1);
            }
            builder.append(current);
            return;
        }
        Visitor.super.visitAttribute(element);
    }

    @Override
    public void visitListing(final Listing element) {
        switch (element.options().getOrDefault("", "")) {
            case "a2s" -> {
                if (configuration.isDataUriForAscii2Svg()) {
                    visitImage(new Macro(
                            "image",
                            new DataUri(() -> new ByteArrayInputStream(YupiikA2s.svg(element.value(), element.options()).getBytes(UTF_8)), "image/svg+xml").base64(),
                            element.options(), false));
                } else {
                    final var clazz = element.options().get("role");
                    if (clazz != null) {
                        builder.append(" <div class=\"").append(clazz.replace('.', ' ').strip()).append("\">\n");
                    }
                    visitPassthroughBlock(new PassthroughBlock(YupiikA2s.svg(element.value(), element.options()), Map.of()));
                    if (clazz != null) {
                        builder.append(" </div>\n");
                    }
                }
            }
            case "mermaid" -> visitMermaid(element);
            default -> visitCode(new Code(element.value(), List.of(), element.options(), false));
        }
    }

    @Override
    public String result() {
        release();
        return builder.toString();
    }

    @Override
    public void visitMacro(final Macro element) {
        if ("indexterm".equals(element.name()) || "indexterm2".equals(element.name())) {
            state.indexTermCount++;
            builder.append(" <a id=\"indexterm-").append(state.indexTermCount).append("\"></a>\n");
            return;
        }
        if ("counter".equals(element.name())) {
            final var counterName = element.label();
            final var value = element.options().getOrDefault("", "1");
            state.counters.put(counterName, Integer.parseInt(value));
            return;
        }
        if ("toc".equals(element.name())) {
            visitToc(state.document.body());
            return;
        }
        if (!element.inline()) {
            final var opts = element.options();
            builder.append(" <div");
            writeCommonAttributes(opts, c -> {
                final var base = element.name() + "block";
                final var f = opts.get("float");
                final var a = opts.get("align");
                final var extra = (f != null ? " " + f : "") + (a != null ? " text-" + a : "");
                return base + extra + (c != null ? " " + c : "");
            });
            builder.append(">\n");
            writeBlockTitle(opts);
            builder.append(" <div class=\"content\">\n");
        }
        switch (element.name()) {
            case "kbd" -> visitKbd(element);
            case "btn" -> visitBtn(element);
            case "menu" -> visitMenu(element);
            case "stem", "latexmath", "asciimath" -> visitStem(element);
            case "pass" -> visitPassthroughInline(element);
            case "icon" -> visitIcon(element);
            case "image" -> visitImage(element);
            case "audio" -> visitAudio(element);
            case "video" -> visitVideo(element);
            case "xref" -> visitXref(element);
            case "footnote" -> visitFootnote(element);
            case "footnoteref" -> visitFootnote(element);
            case "doublefootnote" -> visitFootnote(element);
            case "link" -> {
                final var label = element.options().getOrDefault("", element.label());
                if (label.contains("image:")) { // FIXME: ...we don't want options to be parsed but this looks required
                    try {
                        final var body = subParser.parseBody(new Reader(List.of(label)), subResolver);
                        if (body.children().size() == 1 && body.children().get(0) instanceof Text t && t.style().isEmpty()) {
                            visitLink(new Link(element.label(), new Text(List.of(), t.value(), Map.of()), element.options()));
                        } else {
                            final var html = render(body).result();
                            visitLink(new Link(element.label(), new Text(List.of(), html, Map.of()), Stream.concat(element.options().entrySet().stream(), Stream.of(entry("unsafeHtml", "true")))
                                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b))));
                        }
                    } catch (final RuntimeException re) {
                        visitLink(new Link(element.label(), new Text(List.of(), label, Map.of()), element.options()));
                    }
                } else {
                    visitLink(new Link(element.label(), new Text(List.of(), label, Map.of()), element.options()));
                }
            }
            default -> onMissingMacro(element); // for future extension point
        }
        if (!element.inline()) {
            builder.append(" </div>\n </div>\n");
        }
    }

    protected void visitMermaid(final Listing element) {
        builder
                .append("  <pre class=\"mermaid\">\n")
                .append(element.value().strip())
                .append('\n')
                .append("  </pre>\n");
        usesMermaid = true;
    }


    protected void afterBodyStart() {
        builder.append(attr("renderer-afterBodyStart", ""));
    }

    protected void beforeBodyEnd() {
        if (usesMermaid && !Boolean.parseBoolean(attr("mermaid-skipCdn", "false"))) {
            builder.append("<script type=\"module\">import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';</script>");
        }
        builder.append(attr("renderer-beforeBodyEnd", ""));
    }

    protected void beforeHeadEnd() {
        builder.append(attr("renderer-beforeHeadEnd", ""));
    }

    protected void visitToc(final Body body) {
        final int toclevels = Integer.parseInt(attr("toclevels", "toclevels", "2", state.document.header().attributes()));
        if (toclevels < 1) {
            return;
        }

        builder.append(" <div id=\"toc\" class=\"").append(attr("toc-class", "toc-class", "toc", state.document.header().attributes())).append("\">\n");
        final var tocTitle = attr("toc-title", "toc-title", "Table of Contents", state.document.header().attributes());
        if (tocTitle != null && !tocTitle.isBlank()) {
            builder.append("  <div id=\"toctitle\">").append(tocTitle).append("</div>\n");
        }
        final var docAttrs = state.document.header().attributes();
        final var toc = new TocVisitor(toclevels, 1, docAttrs.get("idprefix"), docAttrs.get("idseparator"));
        toc.visitBody(body);
        builder.append(toc.result());
        builder.append(" </div>\n");
    }

    // todo: enhance
    protected void visitXref(final Macro element) {
        final var outFileSuffix = attr("outfilesuffix", ".html");
        final var relFilePrefix = attr("relfileprefix", "");
        final var relFileSuffix = attr("relfilesuffix", outFileSuffix);
        var target = element.label();
        final int anchor = target.lastIndexOf('#');
        if (anchor > 0) {
            final var page = target.substring(0, anchor);
            if (page.endsWith(".adoc")) {
                target = relFilePrefix + page.substring(0, page.length() - ".adoc".length()) + relFileSuffix + target.substring(anchor);
            }
        } else if (target.endsWith(".adoc")) {
            target = relFilePrefix + target.substring(0, target.length() - ".adoc".length()) + relFileSuffix;
        }
        final var label = element.options().get("");
        if (label != null) {
            builder.append(" <a href=\"").append(target).append("\">").append(parseLabel(label)).append("</a>\n");
        } else {
            ensureXrefCatalog();
            final var displayText = state.xrefCatalog != null ? state.xrefCatalog.get(target) : null;
            builder.append(" <a href=\"").append(target).append("\">").append(displayText != null ? escape(displayText) : element.label()).append("</a>\n");
        }
    }

    // FIXME: should it be done in Parser? but macro label isn't supposed to be interpreted in parser....
    private String parseLabel(final String label) {
        try {
            final var body = subParser.parseBody(new Reader(List.of(label)), subResolver);
            if (body.children().size() == 1 && body.children().get(0) instanceof Text t && t.style().isEmpty()) {
                return label;
            }
            return render(body).result();
        } catch (final RuntimeException re) {
            return label;
        }
    }

    private AsciidoctorLikeHtmlRenderer render(final Body body) {
        final var nested = new AsciidoctorLikeHtmlRenderer(configuration);
        nested.state.sawPreamble = true;
        (body.children().size() == 1 && body.children().get(0) instanceof Paragraph p ?
                p.children() :
                body.children()).stream()
                .map(e -> e instanceof Text t ?
                        new Text(t.style(), t.value(), Stream.concat(
                                        t.options().entrySet().stream(),
                                        Stream.of(entry("nowrap", "true")))
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b))) :
                        e)
                .forEach(nested::visitElement);
        return nested;
    }

    // todo: enhance
    protected void visitImage(final Macro element) {
        if (dataUri && !element.label().startsWith("data:") && !element.options().containsKey("skip-data-uri")) {
            visitImage(new Macro(
                    element.name(), resolver.apply(element.label()).base64(),
                    !element.options().containsKey("") ?
                            Stream.of(element.options(), Map.of("", element.label()))
                                    .filter(Objects::nonNull)
                                    .map(Map::entrySet)
                                    .flatMap(Collection::stream)
                                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)) :
                            element.options(),
                    element.inline()));
            return;
        }

        final String resolvedSrc;
        if (element.label().startsWith("data:")) {
            resolvedSrc = element.label();
        } else {
            String imagesDir = attr("imagesdir", "");
            resolvedSrc = (imagesDir.isEmpty() || imagesDir.endsWith("/")) ? imagesDir + element.label() : imagesDir + "/" + element.label();
        }

        if (!this.state.visitingWrapperLink) {
            String linkValue = element.options().get("link");
            if (linkValue != null) {
                this.state.visitingWrapperLink = true;
                Macro image = new Macro(element.name(), element.label(), element.options(), true);
                Link link = new Link(linkValue, image, element.options());
                this.visitLink(link);
                this.state.visitingWrapperLink = false;

                // Do not render the image yet : it will be rendered as part of the link element
                return;
            }

        }

        final boolean addSpan = element.inline() && !state.visitingWrapperLink;
        if (addSpan) {
            final var opts = element.options();
            final var f = opts.get("float");
            final var r = opts.get("role");
            final var spanClass = "image" + (r != null ? (f != null ? " " + f + " " + r : " " + r) : "") + (f != null && r == null ? " " + f : "");
            builder.append(" <span class=\"").append(spanClass).append("\">");
        }

        builder.append(addSpan ? "" : " ").append("<img src=\"")
                .append(resolvedSrc)
                .append("\" alt=\"").append(element.options().getOrDefault("alt", element.options().getOrDefault("", element.label())))
                .append('"');
        if (element.options().containsKey("width")) {
            builder.append(" width=\"").append(element.options().get("width")).append('"');
        }
        if (element.options().containsKey("height")) {
            builder.append(" height=\"").append(element.options().get("height")).append('"');
        }
        builder.append(voidSlash()).append(">");

        if (addSpan) {
            builder.append("</span>\n");
        } else {
            builder.append("\n");
        }

        if (state.visitingWrapperLink) {
            builder.append(" ");
        }
    }

    protected void visitAudio(final Macro element) {
        if (element.inline()) {
            builder.append(" <div");
            writeCommonAttributes(element.options(), c -> "audioblock" + (c == null ? "" : (' ' + c)));
            builder.append(">\n");
            writeBlockTitle(element.options());
        }
        final var audioOpts = element.options();
        builder.append("  <audio src=\"").append(element.label()).append("\"")
                .append(audioOpts.get("autoplay") != null ? booleanAttr("autoplay") : "")
                .append(audioOpts.get("nocontrols") != null ? "" : booleanAttr("controls"))
                .append(audioOpts.get("loop") != null ? booleanAttr("loop") : "")
                .append(audioOpts.get("muted") != null ? booleanAttr("muted") : "")
                .append(audioOpts.containsKey("preload") ? " preload=\"" + audioOpts.get("preload") + '"' : "")
                .append(">\n");
        builder.append("  Your browser does not support the audio tag.\n");
        builder.append("  </audio>\n");
        if (element.inline()) {
            builder.append(" </div>\n");
        }
    }

    protected void visitVideo(final Macro element) {
        final var options = element.options();
        final var poster = options.getOrDefault("", options.get("poster"));
        final var widthAttr = options.containsKey("width") ? " width=\"" + options.get("width") + '"' : "";
        final var heightAttr = options.containsKey("height") ? " height=\"" + options.get("height") + '"' : "";
        final var docAttrs = state.document.header().attributes();
        final var assetScheme = attr("asset-uri-scheme", "asset-uri-scheme", "https", docAttrs);
        final var assetSchemePrefix = assetScheme.isEmpty() ? "//" : assetScheme + "://";
        if (element.inline()) {
            builder.append(" <div");
            writeCommonAttributes(options, c -> "videoblock" + (c == null ? "" : (' ' + c)));
            builder.append(">\n");
            writeBlockTitle(options);
        }
        if ("youtube".equals(poster) || "vimeo".equals(poster)) {
            final var autoplayParam = options.containsKey("autoplay") ? "&amp;autoplay=1" : "";
            if ("vimeo".equals(poster)) {
                final var videoId = element.label();
                final var startAnchor = options.containsKey("start") ? "#at=" + options.get("start") : "";
                final var loopParam = options.containsKey("loop") ? "&amp;loop=1" : "";
                final var mutedParam = options.containsKey("muted") ? "&amp;muted=1" : "";
                final var hashParam = options.containsKey("hash") ? "&amp;h=" + options.get("hash") : "";
                builder.append("  <iframe").append(widthAttr).append(heightAttr)
                        .append(" src=\"").append(assetSchemePrefix).append("player.vimeo.com/video/").append(videoId)
                        .append(autoplayParam).append(loopParam).append(mutedParam).append(hashParam).append(startAnchor)
                        .append("\" frameborder=\"0\"")
                        .append(options.containsKey("nofullscreen") ? "" : booleanAttr("allowfullscreen"))
                        .append(">\n  </iframe>\n");
            } else { // youtube
                final var relParamVal = options.containsKey("related") ? 1 : 0;
                final var startParam = options.containsKey("start") ? "&amp;start=" + options.get("start") : "";
                final var endParam = options.containsKey("end") ? "&amp;end=" + options.get("end") : "";
                final var loopParam = options.containsKey("loop") ? "&amp;loop=1" : "";
                final var muteParam = options.containsKey("muted") ? "&amp;mute=1" : "";
                final var controlsParam = options.containsKey("nocontrols") ? "&amp;controls=0" : "";
                final var fsParam = options.containsKey("nofullscreen") ? "&amp;fs=0" : "";
                final var fsAttribute = options.containsKey("nofullscreen") ? "" : booleanAttr("allowfullscreen");
                final var modestParam = options.containsKey("modest") ? "&amp;modestbranding=1" : "";
                final var themeParam = options.containsKey("theme") ? "&amp;theme=" + options.get("theme") : "";
                final var hlParam = options.containsKey("lang") ? "&amp;hl=" + options.get("lang") : "";
                final var videoId = element.label();
                final var playlistParam = options.containsKey("list") ? "&amp;list=" + options.get("list") : "";
                builder.append("  <iframe").append(widthAttr).append(heightAttr)
                        .append(" src=\"").append(assetSchemePrefix).append("www.youtube.com/embed/").append(videoId)
                        .append("?rel=").append(relParamVal)
                        .append(startParam).append(endParam)
                        .append(autoplayParam).append(loopParam).append(muteParam)
                        .append(controlsParam).append(playlistParam).append(fsParam)
                        .append(modestParam).append(themeParam).append(hlParam)
                        .append("\" frameborder=\"0\"").append(fsAttribute)
                        .append(">\n  </iframe>\n");
            }
        } else {
            final var startT = options.get("start");
            final var endT = options.get("end");
            final var timeAnchor = (startT != null || endT != null) ? "#t=" + (startT != null ? startT : "") + (endT != null ? "," + endT : "") : "";
            final var posterAttr = poster != null && !poster.isBlank() ? " poster=\"" + poster + '"' : "";
            final var preloadAttr = options.containsKey("preload") ? " preload=\"" + options.get("preload") + '"' : "";
            builder.append("  <video src=\"").append(element.label()).append(timeAnchor).append("\"")
                    .append(widthAttr).append(heightAttr)
                    .append(posterAttr)
                    .append(preloadAttr)
                    .append(options.get("autoplay") != null ? booleanAttr("autoplay") : "")
                    .append(options.get("muted") != null ? booleanAttr("muted") : "")
                    .append(options.get("nocontrols") != null ? "" : booleanAttr("controls"))
                    .append(options.get("loop") != null ? booleanAttr("loop") : "")
                    .append(">\n");
            builder.append("  Your browser does not support the video tag.\n");
            builder.append("  </video>\n");
        }
        if (element.inline()) {
            builder.append(" </div>\n");
        }
    }

    protected void visitPassthroughInline(final Macro element) {
        builder.append(element.label());
    }

    protected void visitBtn(final Macro element) {
        final var label = elementLabel(element);
        builder.append(" <b class=\"button\">").append(escape(label)).append("</b>\n");
    }

    protected void visitKbd(final Macro element) {
        final var label = elementLabel(element);
        final var keys = label.split("\\+");
        if (keys.length > 1) {
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) {
                    builder.append(" + ");
                }
                builder.append("<kbd>").append(escape(keys[i].strip())).append("</kbd>");
            }
            builder.append('\n');
        } else {
            builder.append(" <kbd>").append(escape(label)).append("</kbd>\n");
        }
    }

    protected void visitMenu(final Macro element) {
        final var caret = "&#160;<b class=\"caret\">&#8250;</b> ";
        final var menuPath = element.label();
        final var menuitem = element.options().getOrDefault("", "");
        final var parts = Stream.of(menuPath.split(">")).map(String::strip).toList();
        final var menu = parts.get(parts.size() - 1);
        final var submenus = parts.subList(0, parts.size() - 1);

        if (submenus.isEmpty()) {
            if (!menuitem.isEmpty()) {
                builder.append(" <span class=\"menuseq\">");
                builder.append("<b class=\"menu\">").append(escape(menu)).append("</b>");
                builder.append(caret).append("<b class=\"menuitem\">").append(escape(menuitem)).append("</b>");
                builder.append("</span>\n");
            } else {
                builder.append(" <b class=\"menuref\">").append(escape(menu)).append("</b>\n");
            }
        } else {
            final var submenuJoiner = "</b>" + caret + "<b class=\"submenu\">";
            builder.append(" <span class=\"menuseq\">");
            builder.append("<b class=\"menu\">").append(escape(menu)).append("</b>");
            builder.append(caret).append("<b class=\"submenu\">");
            builder.append(submenus.stream().map(this::escape).collect(joining(submenuJoiner)));
            builder.append("</b>");
            builder.append(caret).append("<b class=\"menuitem\">").append(escape(menuitem)).append("</b>");
            builder.append("</span>\n");
        }
    }

    protected void visitFootnote(final Macro element) {
        final String id;
        final String text;
        if ("footnoteref".equals(element.name()) && element.label().isEmpty()) {
            id = element.options().getOrDefault("", "");
            text = element.options().getOrDefault("opts", "");
        } else {
            id = element.label();
            text = element.options().getOrDefault("", "");
        }
        if (!id.isEmpty() && text.isEmpty()) {
            final var existing = state.footnotes.stream().filter(fn -> id.equals(fn.id)).findFirst();
            if (existing.isPresent()) {
                final var fn = existing.get();
                builder.append(" <sup class=\"footnoteref\">[");
                builder.append("<a class=\"footnote\" href=\"#_footnotedef_").append(fn.index).append("\" title=\"View footnote.\">");
                builder.append(fn.index).append("</a>]</sup>\n");
            } else {
                builder.append(" <sup class=\"footnoteref red\" title=\"Unresolved footnote reference.\">[");
                builder.append(escape(id)).append("]</sup>\n");
            }
        } else {
            state.footnoteIndex++;
            final var fn = new FootNote(state.footnoteIndex, id.isEmpty() ? null : id, text);
            state.footnotes.add(fn);
            builder.append(" <sup class=\"footnote\"");
            if (fn.id != null) {
                builder.append(" id=\"_footnote_").append(escape(fn.id)).append("\"");
            }
            builder.append(">[");
            builder.append("<a id=\"_footnoteref_").append(fn.index).append("\" class=\"footnote\" href=\"#_footnotedef_").append(fn.index).append("\" title=\"View footnote.\">");
            builder.append(fn.index).append("</a>]</sup>\n");
        }
    }

    protected record FootNote(int index, String id, String text) {
    }

    protected void visitIcon(final Macro element) {
        if (!element.inline()) {
            builder.append(' ');
        }
        final var hasRole = element.options().containsKey("role");
        final var link = element.options().get("link");
        if (hasRole) {
            builder.append("<span");
            writeCommonAttributes(element.options(), null);
            builder.append(">");
        }
        if (link != null) {
            builder.append("<a href=\"").append(link).append("\">");
        }
        final var opts = element.options();
        builder.append("<span class=\"icon\"><i class=\"");
        final var label = element.label();
        if (label.startsWith("fa") && !label.contains(" ")) {
            builder.append("fa ");
        }
        builder.append(label);
        ofNullable(opts.getOrDefault("", opts.get("size")))
                .map(size -> " fa-" + size)
                .ifPresent(builder::append);
        ofNullable(opts.get("flip"))
                .map(flip -> " fa-flip-" + flip)
                .ifPresent(builder::append);
        ofNullable(opts.get("rotate"))
                .map(rotate -> " fa-rotate-" + rotate)
                .ifPresent(builder::append);
        ofNullable(opts.get("title"))
                .ifPresent(title -> builder.append("\" title=\"").append(title));
        builder.append("\"></i></span>");
        if (link != null) {
            builder.append("</a>");
        }
        if (hasRole) {
            builder.append("</span>");
        }
        if (!element.inline()) {
            builder.append('\n');
        }
    }

    protected void visitStem(final Macro element) {
        state.hasStem = true;
        if (!element.inline()) {
            builder.append(" <div");
            writeCommonAttributes(element.options(), c -> "stemblock" + (c == null ? "" : (' ' + c)));
            builder.append(">\n");
            writeBlockTitle(element.options());
            builder.append("  <div class=\"content\">\n");
        }

        final var stemType = element.inline() ? element.name() : element.options().getOrDefault("", "stem");
        final boolean latex = "latexmath".equals(stemType) ||
                "latexmath".equals(attr("stem", state.document == null ? Map.of() : state.document.header().attributes()));
        if (latex) {
            if (element.inline()) {
                builder.append(" \\(").append(element.label()).append("\\) ");
            } else {
                builder.append(" \\[").append(element.label()).append("\\] ");
            }
        } else {
            builder.append(" \\$").append(element.label()).append("\\$ ");
        }

        if (!element.inline()) {
            builder.append("  </div>\n");
            builder.append(" </div>\n");
        }
    }

    protected void writeCommonAttributes(final Map<String, String> options, final Function<String, String> classProcessor) {
        var classes = options.get("role");
        if (classes != null) {
            classes = classes.replace('.', ' ');
        }
        if (classProcessor != null) {
            classes = classProcessor.apply(classes);
        }
        if (classes != null && !classes.isBlank()) {
            builder.append(" class=\"").append(classes).append("\"");
        }

        final var id = options.get("id");
        if (id != null && !id.isBlank()) {
            builder.append(" id=\"").append(id).append("\"");
        }

        if (configuration.isSupportDataAttributes()) {
            final var data = options.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("data-") && e.getValue() != null)
                    .map(e -> e.getKey() + "=\"" + e.getValue() + "\"")
                    .toList();
            if (!data.isEmpty()) {
                builder.append(data.stream()
                        .collect(joining(" ", " ", "")));
            }
        }
    }

    protected void handlePreamble(final boolean enableWrappers, final Element next, final Runnable child) {
        if (state.sawPreamble) {
            child.run();
            return;
        }

        final boolean isPreamble = enableWrappers && state.lastElement.size() == 1;
        if (!isPreamble) {
            child.run();
            return;
        }

        final boolean hasSingleSection = state.document != null && state.document.body().children().stream()
                .noneMatch(it -> it instanceof Section s && s.level() > 0);
        if (hasSingleSection) {
            state.sawPreamble = true; // there will be no preamble
            child.run();
            return;
        }

        state.sawPreamble = true;
        builder.append(" <div id=\"preamble\">\n <div class=\"sectionbody\">\n");

        final boolean needsP = next == null || next.type() != PARAGRAPH;
        if (needsP) {
            builder.append(" <p>");
        }
        child.run();
        if (needsP) {
            builder.append("</p>\n");
        }
        builder.append(" </div>\n </div>\n");
    }

    protected void writeBlockTitle(final Map<String, String> options) {
        final var title = options.get("title");
        if (title != null) {
            builder.append("  <div class=\"title\">").append(escape(title)).append("</div>\n");
        }
    }

    protected void writeTableCell(final String tagName, final Element cell,
                                  final String halign, final String baseClass) {
        final var cellOptions = getCellOptions(cell);
        final var valign = cellOptions.get("valign");
        final var colspan = cellOptions.get("colspan");
        final var rowspan = cellOptions.get("rowspan");
        var classes = "tableblock " + baseClass;
        if (valign != null) {
            classes += " valign-" + valign;
        }
        builder.append("    <").append(tagName).append(" class=\"").append(classes).append("\"");
        if (colspan != null) {
            builder.append(" colspan=\"").append(colspan).append('"');
        }
        if (rowspan != null) {
            builder.append(" rowspan=\"").append(rowspan).append('"');
        }
        final var cellBgColor = state.document == null ? null : state.document.header().attributes().get("cellbgcolor");
        if (cellBgColor != null) {
            builder.append(" style=\"background-color: ").append(cellBgColor).append(";\"");
        }
        builder.append(">\n");
        final var isHeader = "th".equals(tagName);
        if (!isHeader) {
            builder.append("<p class=\"tableblock\">\n");
        }
        visitElement(isHeader && cell instanceof Code c ? new Text(List.of(), c.value(), c.options()) : cell);
        if (!isHeader) {
            builder.append("</p>\n");
        }
        builder.append("    </").append(tagName).append(">\n");
    }

    private Map<String, String> getCellOptions(final Element cell) {
        return switch (cell.type()) {
            case TEXT -> ((Text) cell).options();
            case PARAGRAPH -> ((Paragraph) cell).options();
            case CODE -> ((Code) cell).options();
            default -> Map.of();
        };
    }

    protected void onMissingMacro(final Macro element) {
        Visitor.super.visitMacro(element);
    }

    protected String highlightCallOuts(final List<CallOut> callOuts, final String value) {
        if (callOuts.isEmpty()) {
            return value;
        }
        var out = value;
        for (int i = 1; i <= callOuts.size(); i++) {
            out = out.replace(" (" + i + ")", " <b class=\"conum\">(" + i + ")</b>");
        }
        return out;
    }

    protected String escape(final String name) {
        return HtmlEscaping.INSTANCE.apply(name);
    }

    private String voidSlash() {
        final var attrs = state.document == null ? Map.<String, String>of() : state.document.header().attributes();
        final var htmlsyntax = attr("htmlsyntax", attrs);
        return "xml".equals(htmlsyntax) ? "/" : "";
    }

    private String booleanAttr(final String name) {
        final var attrs = state.document == null ? Map.<String, String>of() : state.document.header().attributes();
        return "xml".equals(attr("htmlsyntax", attrs)) ? " " + name + "=\"" + name + "\"" : " " + name;
    }

    private static String elementLabel(final Macro element) {
        final var label = element.label();
        return label.isEmpty() ? element.options().getOrDefault("", "") : label;
    }

    protected String attr(final String key, final String defaultValue) {
        return attr(key, key, defaultValue, state.document.header().attributes());
    }

    protected String attr(final String key, final String defaultKey, final String defaultValue, final Map<String, String> mainMap) {
        return mainMap.getOrDefault(key, configuration.getAttributes().getOrDefault(defaultKey, defaultValue));
    }

    protected String attr(final String key, final Map<String, String> defaultMap) {
        return attr(key, key, null, defaultMap);
    }

    protected boolean isList(final Element.ElementType type) {
        return type == UNORDERED_LIST || type == ORDERED_LIST;
    }

    protected String extractPlainText(final Element element) {
        if (element instanceof Text t) {
            return t.value();
        }
        if (element instanceof Paragraph p) {
            final var sb = new StringBuilder();
            for (final var child : p.children()) {
                sb.append(extractPlainText(child));
            }
            return sb.toString();
        }
        if (element instanceof Code c) {
            return c.value();
        }
        if (element instanceof Link l) {
            return extractPlainText(l.label());
        }
        if (element instanceof Macro m) {
            return m.label();
        }
        return "";
    }

    protected Map<String, String> buildXrefCatalog(final List<Element> children) {
        final var catalog = new HashMap<String, String>();
        buildXrefCatalog(children, catalog);
        return Map.copyOf(catalog);
    }

    private void buildXrefCatalog(final List<Element> children, final HashMap<String, String> catalog) {
        for (final var child : children) {
            if (child instanceof Section s) {
                final var id = s.options().get("id");
                if (id != null) {
                    catalog.putIfAbsent(id, extractPlainText(s.title()).strip());
                }
                buildXrefCatalog(s.children(), catalog);
            } else if (child instanceof FloatingTitle ft) {
                final var id = ft.options().get("id");
                if (id != null) {
                    catalog.putIfAbsent(id, extractPlainText(ft.title()).strip());
                }
            } else if (child instanceof OpenBlock ob) {
                buildXrefCatalog(ob.children(), catalog);
            } else if (child instanceof Quote q) {
                buildXrefCatalog(q.children(), catalog);
            } else if (child instanceof Paragraph p) {
                final var id = p.options().get("id");
                if (id != null) {
                    catalog.putIfAbsent(id, extractPlainText(p).strip());
                }
            }
        }
    }

    protected void ensureXrefCatalog() {
        if (state.xrefCatalog == null && state.document != null) {
            state.xrefCatalog = buildXrefCatalog(state.document.body().children());
        }
    }

    private void release() {
        state.close();
        if (resolver != null) {
            resolver.close();
        }
    }

    private String extractAlignment(final String col) {
        if (col.contains("^")) {
            return "center";
        }
        if (col.contains(">")) {
            return "right";
        }
        return "left";
    }

    private int extractNumbers(final String col) {
        int i = 0;
        while (col.length() > i && Character.isDigit(col.charAt(i))) {
            i++;
        }
        try {
            if (i == 0) {
                return 1;
            }
            return Integer.parseInt(col.substring(0, i));
        } catch (final NumberFormatException nfe) {
            return 1;
        }
    }

    private void visitAsAdmonition(final Admonition.Level level, final OpenBlock element) {
        visitAdmonition(new Admonition(
                level, element.children().size() == 1 ?
                element.children().get(0) :
                new Paragraph(element.children(),
                        element.options().size() == 1 ?
                                Map.of() :
                                element.options().entrySet().stream()
                                        .filter(it -> !"".equals(it.getKey()))
                                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue))),
                element.options()));
    }

    @Getter
    public static class Configuration {
        private String sectionTag = "div";
        private boolean dataUriForAscii2Svg = true;
        private boolean skipSectionBody = false;
        private boolean skipGlobalContentWrapper = false;
        private boolean supportDataAttributes = true;
        private DataResolver resolver;
        private Path assetsBase;
        private Map<String, String> attributes = Map.of();

        public Configuration setDataUriForAscii2Svg(final boolean dataUriForAscii2Svg) {
            this.dataUriForAscii2Svg = dataUriForAscii2Svg;
            return this;
        }

        public Configuration setSectionTag(final String sectionTag) {
            this.sectionTag = sectionTag;
            return this;
        }

        public Configuration setSkipGlobalContentWrapper(final boolean skipGlobalContentWrapper) {
            this.skipGlobalContentWrapper = skipGlobalContentWrapper;
            return this;
        }

        public Configuration setSkipSectionBody(final boolean skipSectionBody) {
            this.skipSectionBody = skipSectionBody;
            return this;
        }

        /**
         * @param supportDataAttributes should {@code data-xxx} attributes be forwarded on div level.
         * @return this.
         */
        public Configuration setSupportDataAttributes(final boolean supportDataAttributes) {
            this.supportDataAttributes = supportDataAttributes;
            return this;
        }

        public Configuration setResolver(final DataResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        public Configuration setAssetsBase(final Path assetsBase) {
            this.assetsBase = assetsBase;
            return this;
        }

        public Configuration setAttributes(final Map<String, String> attributes) {
            this.attributes = attributes;
            return this;
        }
    }

    protected static class State implements AutoCloseable {
        protected static final Document EMPTY_DOC = new Document(new Header("", List.of(), null, Map.of()), new Body(List.of()));

        protected Document document = EMPTY_DOC;
        protected List<Element> currentChain = null;
        protected boolean hasStem = false;
        protected boolean nowrap = false;
        protected boolean sawPreamble = false;
        protected boolean inCallOut = false;
        protected int footnoteIndex = 0;
        protected int indexTermCount = 0;
        protected final Map<String, Integer> counters = new HashMap<>();
        protected final Map<Integer, Integer> sectionNumberCounters = new HashMap<>();
        protected final List<FootNote> footnotes = new ArrayList<>();

        // Indicates that we currently are visiting a link wrapping an element (like an image)
        protected boolean visitingWrapperLink = false;

        protected final List<Element> lastElement = new ArrayList<>(4);

        // Catalog of id → title for xref resolution
        protected Map<String, String> xrefCatalog = null;

        @Override
        public void close() {
            document = EMPTY_DOC;
            currentChain = null;
            sawPreamble = false;
            inCallOut = false;
            lastElement.clear();
            footnoteIndex = 0;
            indexTermCount = 0;
            counters.clear();
            sectionNumberCounters.clear();
            footnotes.clear();
        }

        private void stackChain(final List<Element> next, final Runnable run) {
            final var current = currentChain;
            try {
                currentChain = next;
                run.run();
            } finally {
                currentChain = current;
            }
        }
    }
}
