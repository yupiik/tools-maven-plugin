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
import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.CallOut;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.DescriptionList;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Header;
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
        if (!"none".equals(attr("toc", "toc", "none", state.document.header().attributes()))) {
            visitToc(body);
        }

        state.stackChain(body.children(), () -> Visitor.super.visitBody(body));
    }

    @Override
    public void visitConditionalBlock(final ConditionalBlock element) {
        state.stackChain(element.children(), () -> Visitor.super.visitConditionalBlock(element));
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
        final boolean contentOnly = Boolean.parseBoolean(configuration.getAttributes().getOrDefault("noheader", "false"));
        if (!contentOnly) {
            final var attributes = document.header().attributes();

            builder.append("<!DOCTYPE html>\n");
            builder.append("<html");
            if (attr("nolang", attributes) == null) {
                final var lang = attr("lang", attributes);
                builder.append(" lang=\"").append(lang == null ? "en" : lang).append('"');
            }
            builder.append(">\n");
            builder.append("<head>\n");

            final var encoding = attr("encoding", attributes);
            builder.append(" <meta charset=\"").append(encoding == null ? "UTF-8" : encoding).append("\">\n");

            builder.append(" <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n");

            final var appName = attr("app-name", attributes);
            if (appName != null) {
                builder.append(" <meta name=\"application-name\" content=\"").append(appName).append("\">\n");
            }
            final var description = attr("description", attributes);
            if (description != null) {
                builder.append(" <meta name=\"description\" content=\"").append(description).append("\">\n");
            }
            final var keywords = attr("keywords", attributes);
            if (keywords != null) {
                builder.append(" <meta name=\"keywords\" content=\"").append(keywords).append("\">\n");
            }
            final var author = attr("author", attributes);
            if (author != null) {
                builder.append(" <meta name=\"author\" content=\"").append(author).append("\">\n");
            }
            final var copyright = attr("copyright", attributes);
            if (copyright != null) {
                builder.append(" <meta name=\"copyright\" content=\"").append(copyright).append("\">\n");
            }

            if (attr("asciidoctor-css", "asciidoctor-css", null, attributes) != null) {
                builder.append(" <link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/asciidoctor.js/1.5.9/css/asciidoctor.min.css\" integrity=\"sha512-lb4ZuGfCVoGO2zu/TMakNlBgRA6mPXZ0RamTYgluFxULAwOoNnBIZaNjsdfhnlKlIbENaQbEAYEWxtzjkB8wsQ==\" crossorigin=\"anonymous\" referrerpolicy=\"no-referrer\" />\n");
            }
            builder.append(attr("custom-css", "custom-css", "", attributes));

            // todo: favicon, highlighter, etc...
            beforeHeadEnd();
            builder.append("</head>\n");

            builder.append("<body");
            ofNullable(attr("body-classes", "body-classes", null, attributes))
                    .ifPresent(c -> builder.append(" class=\"").append(c).append("\""));
            builder.append(">\n");
            builder.append(attr("header-html", "header-html", "", document.header().attributes()));
            afterBodyStart();

            if (!configuration.isSkipGlobalContentWrapper()) {
                builder.append(" <div id=\"content\">\n");
            }
        }
        Visitor.super.visit(document);
        if (!contentOnly) {
            if (!configuration.isSkipGlobalContentWrapper()) {
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
        // todo: here we need to impl icons to render it more elegantly
        builder.append(" <div class=\"admonitionblock ").append(element.level().name().toLowerCase(ROOT)).append("\">\n");
        builder.append("""
                          <table>
                            <tbody>
                             <tr>
                              <td class="icon">
                        """)
                .append("     <div class=\"title\">").append(element.level().name()).append("</div>\n")
                .append("       </td>\n")
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
            handlePreamble(true, element, () -> {
                if (!state.nowrap) {
                    builder.append(" <div");
                    writeCommonAttributes(element.options(), c -> "paragraph" + (c != null ? ' ' + c : ""));
                    builder.append(">\n");
                }

                final boolean addP = !state.nowrap && !preambleWasHandled && state.sawPreamble && element.children().stream()
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

                if (!state.nowrap) {
                    builder.append(" </div>\n");
                }
            });
        });
    }

    @Override
    public void visitHeader(final Header header) {
        if (header.attributes().get("notitle") == null &&
                !Boolean.parseBoolean(configuration.getAttributes().getOrDefault("noheader", "false")) &&
                !header.title().isBlank()) {
            builder.append(" <h1>").append(escape(header.title())).append("</h1>\n");
        }

        final var details = new StringBuilder();
        {
            int authorIdx = 1;
            final var mails = header.author().name().split(",");
            for (final var name : header.author().name().split(",")) {
                if (name.isBlank()) {
                    continue;
                }
                details.append("<span class=\"author author-").append(authorIdx).append("\">").append(escape(name)).append("</span>\n");

                final var mail = mails.length > (authorIdx - 1) ? mails[authorIdx - 1] : null;
                if (mail != null) {
                    details.append("<span class=\"email email-").append(authorIdx++).append("\">").append(escape(mail)).append("</span>\n");
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
            final var title = titleRenderer.result();

            builder.append(" <").append(configuration.getSectionTag());
            writeCommonAttributes(element.options(), c -> "sect" + (element.level() - 1) + (c == null ? "" : (' ' + c)));
            if (!element.options().containsKey("id")) {
                builder.append(" id=\"").append(IdGenerator.forTitle(title)).append("\"");
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
    public void visitLineBreak(final LineBreak element) {
        builder.append(" <br>\n");
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

        final var window = element.options().get("window");
        if (window != null) {
            builder.append(" target=\"").append(window).append("\"");
        }

        final var nofollow = element.options().get("nofollow");
        final boolean noopener = "_blank".equals(window) || element.options().get("noopener") != null;
        if (nofollow != null) {
            builder.append(" rel=\"nofollow");
            if (noopener) {
                builder.append(" noopener");
            }
            builder.append("\"");
        } else if (noopener) {
            builder.append(" rel=\"noopener\"");
        }

        builder.append(">");
        if (element.options().containsKey("unsafeHtml")) {
            builder.append(element.label());
        } else {
            var label = element.label();
            if (label.contains("://") && attr("hide-uri-scheme", element.options()) != null) {
                label = label.substring(label.indexOf("://") + "://".length());
            }
            builder.append(escape(label));
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
        state.stackChain(new ArrayList<>(element.children().values()), () -> {
            builder.append(" <dl");
            writeCommonAttributes(element.options(), null);
            builder.append(">\n");
            for (final var elt : element.children().entrySet()) {
                builder.append("  <dt>");
                visitElement(elt.getKey());
                builder.append("</dt>\n");
                builder.append("  <dd>\n");
                visitElement(elt.getValue());
                builder.append("</dd>\n");
            }
            builder.append(" </dl>\n");
        });
    }

    @Override
    public void visitUnOrderedList(final UnOrderedList element) {
        if (element.children().isEmpty()) {
            return;
        }
        state.stackChain(element.children(), () -> {
            builder.append(" <div");
            writeCommonAttributes(element.options(), c -> "ulist" + (c != null ? ' ' + c : ""));
            builder.append(">\n");
            builder.append(" <ul>\n");
            visitListElements(element.children());
            builder.append(" </ul>\n");
            builder.append(" </div>\n");
        });
    }


    @Override
    public void visitOrderedList(final OrderedList element) {
        if (element.children().isEmpty()) {
            return;
        }
        state.stackChain(element.children(), () -> {
            builder.append(" <ol");
            writeCommonAttributes(element.options(), null);
            builder.append(">\n");
            visitListElements(element.children());
            builder.append(" </ol>\n");
        });
    }

    private void visitListElements(final List<Element> element) {
        for (final var elt : element) {
            builder.append("  <li>\n");
            visitElement(elt);
            builder.append("  </li>\n");
        }
    }

    @Override
    public void visitText(final Text element) {
        final var useWrappers = element.options().get("nowrap") == null;

        final boolean preambleSaw = state.sawPreamble;
        handlePreamble(useWrappers, element, () -> {
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
                        case BOLD -> "b";
                        case ITALIC -> "i";
                        case EMPHASIS -> "em";
                        case SUB -> "sub";
                        case SUP -> "sup";
                        case MARK -> "span";
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
            builder.append("<code>").append(escape(element.value().strip())).append("</code>");
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

        builder.append(" <div class=\"listingblock\">\n <div class=\"content\">\n");
        builder.append(" <pre class=\"highlightjs highlight\">");
        builder.append("<code");
        writeCommonAttributes(element.options(), c -> (lang != null ? "language-" + lang + (c != null ? ' ' + c : "") : c) + " hljs");
        if (lang != null) {
            builder.append(" data-lang=\"").append(lang).append("\"");
        }
        builder.append(">");
        final var html = escape(element.value()).strip();
        builder.append("false".equalsIgnoreCase(element.options().get("hightlight-callouts")) ? html : highlightCallOuts(element.callOuts(), html));
        builder.append("</code></pre>\n </div>\n </div>\n");

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
        final var classes = "tableblock" +
                " frame-" + attr("frame", "table-frame", "all", element.options()) +
                " grid-" + attr("grid", "table-grid", "all", element.options()) +
                ofNullable(attr("stripes", "table-stripes", null, element.options()))
                        .map(it -> " stripes-" + it)
                        .orElse("") +
                (autowidth && !element.options().containsKey("width") ? " fit-content" : "") +
                ofNullable(element.options().get("tablepcwidth"))
                        .filter(it -> !"100".equals(it))
                        .map(it -> " width=\"" + it + "\"")
                        .orElse(" stretch") +
                (element.options().containsKey("float") ? " float" : "");

        builder.append(" <table");
        writeCommonAttributes(element.options(), c -> classes + (c == null ? "" : (' ' + c)));
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

            builder.append("  <colgroup>\n");
            if (autowidth) {
                IntStream.range(0, firstRow.size()).forEach(i -> builder.append("   <col>\n"));
            } else {
                final int totalWeight = cols.stream().mapToInt(Integer::intValue).sum();
                final int pc = (int) (100. / Math.max(1, totalWeight));
                IntStream.range(0, cols.size()).forEach(i -> builder.append("   <col width=\"").append(cols.get(i) * pc).append("%\">\n"));
            }
            builder.append("  </colgroup>\n");

            // todo: handle headers+classes without assuming first row is headers - update parser - an options would be better pby?
            builder.append("  <thead>\n");
            builder.append("   <tr>\n");
            firstRow.forEach(it -> {
                builder.append("    <th>\n");
                visitElement(it);
                builder.append("    </th>\n");
            });
            builder.append("   </tr>\n");
            builder.append("  </thead>\n");

            if (element.elements().size() > 1) {
                builder.append("  <tbody>\n");
                element.elements().stream().skip(1).forEach(row -> {
                    builder.append("   <tr>\n");
                    row.forEach(col -> {
                        builder.append("    <td>\n");
                        visitElement(col);
                        builder.append("    </td>\n");
                    });
                    builder.append("   </tr>\n");
                });
                builder.append("  </tbody>\n");
            }
        }

        builder.append(" </table>\n");
    }

    @Override
    public void visitQuote(final Quote element) {
        builder.append(" <div");
        writeCommonAttributes(element.options(), null);
        builder.append(">\n");

        writeBlockTitle(element.options());

        builder.append("  <blockquote>\n");
        Visitor.super.visitQuote(element);
        builder.append("  </blockquote>\n");

        final var attribution = ofNullable(element.options().get("attribution"))
                .orElseGet(() -> element.options().get("citetitle"));
        if (attribution != null) {
            builder.append("  <div class=\"attribution\">\n").append(escape(attribution)).append("\n  </div>\n");
        }

        builder.append(" </div>");
    }

    @Override
    public void visitAnchor(final Anchor element) {
        visitLink(new Link("#" + element.value(), element.label() == null || element.label().isBlank() ? element.value() : element.label(), Map.of()));
    }

    @Override
    public void visitPassthroughBlock(final PassthroughBlock element) {
        switch (element.options().getOrDefault("", "")) {
            case "stem" -> visitStem(new Macro("stem", element.value(), element.options(), false));
            default -> builder.append("\n").append(element.value()).append("\n");
        }
    }

    @Override
    public void visitOpenBlock(final OpenBlock element) {
        state.stackChain(element.children(), () -> {
            boolean skipDiv = false;
            if (element.options().get("abstract") != null) {
                builder.append(" <div");
                writeCommonAttributes(element.options(), c -> "abstract quoteblock" + (c == null ? "" : (' ' + c)));
                builder.append(">\n");
            } else if (element.options().get("partintro") != null) {
                builder.append(" <div");
                writeCommonAttributes(element.options(), c -> "openblock " + (c == null ? "" : (' ' + c)));
                builder.append(">\n");
            } else {
                skipDiv = true;
            }
            writeBlockTitle(element.options());
            builder.append("  <div");
            if (skipDiv) {
                writeCommonAttributes(element.options(), c -> "content" + (c == null ? "" : (' ' + c)));
            }
            builder.append(">\n");
            Visitor.super.visitOpenBlock(element);
            builder.append("  </div>\n");
            if (!skipDiv) {
                builder.append(" </div>\n");
            }
        });
    }

    @Override
    public ConditionalBlock.Context context() {
        return configuration.getAttributes()::get;
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
        if (!element.inline()) {
            builder.append(" <div class=\"").append(element.name()).append("block\">\n <div class=\"content\">\n");
        }
        switch (element.name()) {
            case "kbd" -> visitKbd(element);
            case "btn" -> visitBtn(element);
            case "stem" -> visitStem(element);
            case "pass" -> visitPassthroughInline(element);
            case "icon" -> visitIcon(element);
            case "image" -> visitImage(element);
            case "audio" -> visitAudio(element);
            case "video" -> visitVideo(element);
            case "xref" -> visitXref(element);
            case "link" -> {
                final var label = element.options().getOrDefault("", element.label());
                if (label.contains("image:")) { // FIXME: ...we don't want options to be parsed but this looks required
                    try {
                        final var parser = new Parser(configuration.getAttributes() == null ? Map.of() : configuration.getAttributes());
                        final var body = parser.parseBody(new Reader(List.of(label)), new LocalContextResolver(configuration.getAssetsBase()));
                        if (body.children().size() == 1 && body.children().get(0) instanceof Text t && t.style().isEmpty()) {
                            visitLink(new Link(element.label(), t.value(), element.options()));
                        } else {
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

                            final var html = nested.result();
                            visitLink(new Link(element.label(), html, Stream.concat(element.options().entrySet().stream(), Stream.of(entry("unsafeHtml", "true")))
                                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b))));
                        }
                    } catch (final RuntimeException re) {
                        visitLink(new Link(element.label(), label, element.options()));
                    }
                } else {
                    visitLink(new Link(element.label(), label, element.options()));
                }
            }
            // todo: menu, doublefootnote, footnote
            default -> onMissingMacro(element); // for future extension point
        }
        if (!element.inline()) {
            builder.append(" </div>\n </div>\n");
        }
    }

    protected void afterBodyStart() {
        // no-op
    }

    protected void beforeBodyEnd() {
        // no-op
    }

    protected void beforeHeadEnd() {
        // no-op
    }

    protected void visitToc(final Body body) {
        final int toclevels = Integer.parseInt(attr("toclevels", "toclevels", "2", state.document.header().attributes()));
        if (toclevels < 1) {
            return;
        }

        builder.append(" <div id=\"toc\" class=\"").append(attr("toc-class", "toc-class", "toc", state.document.header().attributes())).append("\">\n");
        if (state.document.header().title() != null && !state.document.header().title().isBlank()) {
            builder.append("  <div id=\"toctitle\">").append(state.document.header().title()).append("</div>\n");
        }
        final var toc = new TocVisitor(toclevels, 1);
        toc.visitBody(body);
        builder.append(toc.result());
        builder.append(" </div>\n");
    }

    // todo: enhance
    protected void visitXref(final Macro element) {
        var target = element.label();
        final int anchor = target.lastIndexOf('#');
        if (anchor > 0) {
            final var page = target.substring(0, anchor);
            if (page.endsWith(".adoc")) {
                target = page.substring(0, page.length() - ".adoc".length()) + ".html" + target.substring(anchor);
            }
        } else if (target.endsWith(".adoc")) {
            target = target.substring(0, target.length() - ".adoc".length()) + ".html";
        }
        final var label = element.options().get("");
        builder.append(" <a href=\"").append(target).append("\">").append(label == null ? element.label() : label).append("</a>\n");
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

        builder.append(" <img src=\"").append(element.label())
                .append("\" alt=\"").append(element.options().getOrDefault("", element.options().getOrDefault("alt", element.label())))
                .append('"');
        writeCommonAttributes(element.options(), null);
        builder.append(">\n");
    }

    protected void visitAudio(final Macro element) {
        builder.append(" <div");
        writeCommonAttributes(element.options(), c -> "audioblock" + (c == null ? "" : (' ' + c)));
        builder.append(">\n");
        writeBlockTitle(element.options());
        builder.append("  <audio src=\"").append(element.label()).append("\"")
                .append(element.options().get("autoplay") != null ? " autoplay" : "").
                append(element.options().get("nocontrols") != null ? " nocontrols" : "")
                .append(element.options().get("loop") != null ? " loop" : "")
                .append(">\n");
        builder.append("  Your browser does not support the audio tag.\n");
        builder.append("  </audio>\n");
        builder.append(" </div>\n");
    }

    // todo: support youtube etc? not sure it makes much sense
    protected void visitVideo(final Macro element) {
        builder.append(" <div");
        writeCommonAttributes(element.options(), c -> "videoblock" + (c == null ? "" : (' ' + c)));
        builder.append(">\n");
        writeBlockTitle(element.options());
        builder.append("  <video src=\"").append(element.label()).append("\"")
                .append(element.options().get("autoplay") != null ? " autoplay" : "").
                append(element.options().get("nocontrols") != null ? " nocontrols" : "")
                .append(element.options().get("loop") != null ? " loop" : "")
                .append(">\n");
        builder.append("  Your browser does not support the video tag.\n");
        builder.append("  </video>\n");
        builder.append(" </div>\n");
    }

    protected void visitPassthroughInline(final Macro element) {
        builder.append(element.label());
    }

    protected void visitBtn(final Macro element) {
        builder.append(" <b class=\"button\">").append(escape(element.label())).append("</b>\n");
    }

    protected void visitKbd(final Macro element) {
        builder.append(" <kbd>").append(escape(element.label())).append("</kbd>\n");
    }

    protected void visitIcon(final Macro element) {
        if (!element.inline()) {
            builder.append(' ');
        }
        final var hasRole = element.options().containsKey("role");
        if (hasRole) {
            builder.append("<span");
            writeCommonAttributes(element.options(), null);
            builder.append(">");
        }
        builder.append("<span class=\"icon\"><i class=\"");
        builder.append(element.label().startsWith("fa") && !element.label().contains(" ") ? "fa " : "")
                .append(element.label())
                .append(ofNullable(element.options().getOrDefault("", element.options().get("size")))
                        .map(size -> " fa-" + size)
                        .orElse(""));
        builder.append("\"></i></span>");
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

        final boolean latex = "latexmath".equals(attr("stem", state.document == null ? Map.of() : state.document.header().attributes()));
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

    protected String attr(final String key, final String defaultKey, final String defaultValue, final Map<String, String> mainMap) {
        return mainMap.getOrDefault(key, configuration.getAttributes().getOrDefault(defaultKey, defaultValue));
    }

    protected String attr(final String key, final Map<String, String> defaultMap) {
        return attr(key, key, null, defaultMap);
    }

    protected boolean isList(final Element.ElementType type) {
        return type == UNORDERED_LIST || type == ORDERED_LIST;
    }

    private void release() {
        state.close();
        if (resolver != null) {
            resolver.close();
        }
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
        protected static final Document EMPTY_DOC = new Document(new Header("", null, null, Map.of()), new Body(List.of()));

        protected Document document = EMPTY_DOC;
        protected List<Element> currentChain = null;
        protected boolean hasStem = false;
        protected boolean nowrap = false;
        protected boolean sawPreamble = false;
        protected boolean inCallOut = false;
        protected final List<Element> lastElement = new ArrayList<>(4);

        @Override
        public void close() {
            document = EMPTY_DOC;
            currentChain = null;
            sawPreamble = false;
            inCallOut = false;
            lastElement.clear();
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
