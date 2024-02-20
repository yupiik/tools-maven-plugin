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

import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsciidoctorLikeHtmlRendererTest {
    @Test
    void metaInPreamble() {
        // this is not a strict preamble as of today but this concept should likely be revisited since
        // it fakes rendering too much in style and enforces an undesired id in several cases
        assertRendering("""
                = Yupiik Fusion 1.0.8 Released
                                
                [.metadata]
                [.mr-2.category-release]#icon:fas fa-gift[]#, [.metadata-authors]#link:http://localhost:4200/blog/author/francois-papon/page-1.html[Francois Papon]#, [.metadata-published]#2023-09-26#, [.metadata-readingtime]#49 sec read#
                                
                [abstract]
                Blabla.
                                
                == What's new?
                                
                * [dependencies] Upgrade to Apache Tomcat 10.1.13.
                """, """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                 <meta charset="UTF-8">
                 <meta http-equiv="X-UA-Compatible" content="IE=edge">
                </head>
                <body>
                 <div id="content">
                 <h1>Yupiik Fusion 1.0.8 Released</h1>
                 <div id="preamble">
                 <div class="sectionbody">
                 <div class="paragraph metadata">
                 <p><span class="mr-2 category-release"><span class="icon"><i class="fas fa-gift"></i></span></span>,  <a href="http://localhost:4200/blog/author/francois-papon/page-1.html" class="metadata-authors">Francois Papon</a>
                , <span class="metadata-published">2023-09-26</span>, <span class="metadata-readingtime">49 sec read</span></p>
                 </div>
                 </div>
                 </div>
                 <div class="sect1">
                 <div class="quoteblock abstract">
                  <blockquote>
                Blabla.  </blockquote>
                 </div> </div>
                 <div class="sect1" id="_whats_new">
                  <h2>What's new?</h2>
                 <div class="sectionbody">
                 <div class="ulist">
                 <ul>
                  <li>
                 <p>
                [dependencies] Upgrade to Apache Tomcat 10.1.13.
                 </p>
                  </li>
                 </ul>
                 </div>
                 </div>
                 </div>
                 </div>
                </body>
                </html>
                """);
    }

    @Test
    void renderHtml() {
        assertRendering("""
                        = Main title
                                        
                        Some text.
                                        
                        == Second part
                                        
                        This is a snippet:
                                        
                        [source,java]
                        ----
                        public record Foo() {}
                        ----
                        """,
                """
                        <!DOCTYPE html>
                        <html lang="en">
                        <head>
                         <meta charset="UTF-8">
                         <meta http-equiv="X-UA-Compatible" content="IE=edge">
                        </head>
                        <body>
                         <div id="content">
                         <h1>Main title</h1>
                         <div id="preamble">
                         <div class="sectionbody">
                         <p> <div class="paragraph">
                         <p>
                        Some text.
                         </p>
                         </div>
                        </p>
                         </div>
                         </div>
                         <div class="sect1" id="_second_part">
                          <h2>Second part</h2>
                         <div class="sectionbody">
                         <div class="paragraph">
                         <p>
                        This is a snippet:
                         </p>
                         </div>
                         <div class="listingblock">
                         <div class="content">
                         <pre class="highlightjs highlight"><code class="language-java hljs" data-lang="java">public record Foo() {}</code></pre>
                         </div>
                         </div>
                         </div>
                         </div>
                         </div>
                        </body>
                        </html>
                        """);
    }

    @Test
    void callout() {
        assertRenderingContent("""
                == Enums
                                                            
                Enumerations (de)serialization behavior can be customized by using some specific methods:
                           
                [source,java]
                ----
                public enum MyEnum {
                    A, B;
                           
                    public String toJsonString() { <1>
                        return this == A ? "first" : "second";
                    }
                           
                    public static MyEnum fromJsonString(final String v) { <2>
                        return switch (v) {
                            case "first" -> MyEnum.A;
                            case "second" -> MyEnum.B;
                            default -> throw new IllegalArgumentException("Unsupported '" + v + "'");
                        };
                    }
                }
                ----
                <.> `toJsonString` is an instance method with no parameter used to replace `.name()` call during serialization,
                <.> `fromJsonString` is a static method with a `String` parameter used to replace `.valueOf(String)` call during deserialization.
                """, """
                 <div class="sect1" id="_enums">
                  <h2>Enums</h2>
                 <div class="sectionbody">
                 <div class="paragraph">
                 <p>
                Enumerations (de)serialization behavior can be customized by using some specific methods:
                 </p>
                 </div>
                 <div class="listingblock">
                 <div class="content">
                 <pre class="highlightjs highlight"><code class="language-java hljs" data-lang="java">public enum MyEnum {
                    A, B;
                                
                    public String toJsonString() { <b class="conum">(1)</b>
                        return this == A ? &quot;first&quot; : &quot;second&quot;;
                    }
                                
                    public static MyEnum fromJsonString(final String v) { <b class="conum">(2)</b>
                        return switch (v) {
                            case &quot;first&quot; -&gt; MyEnum.A;
                            case &quot;second&quot; -&gt; MyEnum.B;
                            default -&gt; throw new IllegalArgumentException(&quot;Unsupported '&quot; + v + &quot;'&quot;);
                        };
                    }
                }</code></pre>
                 </div>
                 </div>
                 <div class="colist arabic">
                  <ol>
                   <li>
                <code>toJsonString</code> <span>
                 is an instance method with no parameter used to replace\s
                 </span>
                <code>.name()</code> <span>
                 call during serialization,
                 </span>
                   </li>
                   <li>
                <code>fromJsonString</code> <span>
                 is a static method with a\s
                 </span>
                <code>String</code> <span>
                 parameter used to replace\s
                 </span>
                <code>.valueOf(String)</code> <span>
                 call during deserialization.
                 </span>
                   </li>
                  </ol>
                 </div>
                 </div>
                 </div>
                """);
    }

    @Test
    void ol() {
        assertRenderingContent("""
                . first
                . second
                . third""", """
                 <ol>
                  <li>
                 <p>
                first
                 </p>
                  </li>
                  <li>
                 <p>
                second
                 </p>
                  </li>
                  <li>
                 <p>
                third
                 </p>
                  </li>
                 </ol>
                """);
    }

    @Test
    void ul() {
        assertRenderingContent("""
                * first
                * second
                * third""", """
                 <div class="ulist">
                 <ul>
                  <li>
                 <p>
                first
                 </p>
                  </li>
                  <li>
                 <p>
                second
                 </p>
                  </li>
                  <li>
                 <p>
                third
                 </p>
                  </li>
                 </ul>
                 </div>
                """);
    }

    @Test
    void ulWithOptions() {
        assertRenderingContent("""
                [role="blog-links blog-links-page"]
                * link:http://localhost:4200/blog/index.html[All posts,role="blog-link-all"]
                * link:http://localhost:4200/blog/page-2.html[Next,role="blog-link-next"]
                """, """
                 <div class="ulist blog-links blog-links-page">
                 <ul>
                  <li>
                 <p> <a href="http://localhost:4200/blog/index.html" class="blog-link-all">All posts</a>
                </p>  </li>
                  <li>
                 <p> <a href="http://localhost:4200/blog/page-2.html" class="blog-link-next">Next</a>
                </p>  </li>
                 </ul>
                 </div>
                """);
    }

    @Test
    void dl() {
        assertRenderingContent("""
                first:: one
                second:: two""", """
                 <dl>
                  <dt>first</dt>
                  <dd>
                one</dd>
                  <dt>second</dt>
                  <dd>
                two</dd>
                 </dl>
                """);
    }

    @Test
    void admonition() {
        assertRenderingContent("NOTE: this is an important note.", """
                 <div class="admonitionblock note">
                  <table>
                    <tbody>
                     <tr>
                      <td class="icon">
                     <div class="title">NOTE</div>
                       </td>
                      <td class="content">
                this is an important note.    </td>
                   </tr>
                      </tbody>
                  </table>
                 </div>
                """);
    }

    @Test
    void table() {
        assertRenderingContent("""
                [cols="1,1"]
                |===
                |Cell in column 1, header row |Cell in column 2, header row\s
                                
                |Cell in column 1, row 2
                |Cell in column 2, row 2
                                
                |Cell in column 1, row 3
                |Cell in column 2, row 3
                                
                |Cell in column 1, row 4
                |Cell in column 2, row 4
                |===""", """
                 <table class="tableblock frame-all grid-all stretch">
                  <colgroup>
                   <col width="50%">
                   <col width="50%">
                  </colgroup>
                  <thead>
                   <tr>
                    <th>
                Cell in column 1, header row     </th>
                    <th>
                Cell in column 2, header row    </th>
                   </tr>
                  </thead>
                  <tbody>
                   <tr>
                    <td>
                Cell in column 1, row 2    </td>
                    <td>
                Cell in column 2, row 2    </td>
                   </tr>
                   <tr>
                    <td>
                Cell in column 1, row 3    </td>
                    <td>
                Cell in column 2, row 3    </td>
                   </tr>
                   <tr>
                    <td>
                Cell in column 1, row 4    </td>
                    <td>
                Cell in column 2, row 4    </td>
                   </tr>
                  </tbody>
                 </table>
                """);
    }

    @Test
    void quote() {
        assertRenderingContent("""
                > > What's new?
                >
                > I've got Markdown in my AsciiDoc!
                >
                > > Like what?
                >
                > * Blockquotes
                > * Headings
                > * Fenced code blocks
                >
                > > Is there more?
                >
                > Yep. AsciiDoc and Markdown share a lot of common syntax already.""", """
                 <div>
                  <blockquote>
                 <div>
                  <blockquote>
                What's new?  </blockquote>
                 </div>I've got Markdown in my AsciiDoc! <div>
                  <blockquote>
                Like what?  </blockquote>
                 </div> <div class="ulist">
                 <ul>
                  <li>
                 <p>
                Blockquotes
                 </p>
                  </li>
                  <li>
                 <p>
                Headings
                 </p>
                  </li>
                  <li>
                 <p>
                Fenced code blocks
                 </p>
                  </li>
                 </ul>
                 </div>
                 <div>
                  <blockquote>
                Is there more?  </blockquote>
                 </div>Yep. AsciiDoc and Markdown share a lot of common syntax already.  </blockquote>
                 </div>""");
    }

    @Test
    void passthrough() {
        assertRenderingContent("""
                ++++
                <div id="test">Content</div>
                ++++""", """
                                
                <div id="test">Content</div>
                """);
    }

    @Test
    void stem() {
        assertRenderingContent("""
                = Some formulas
                                
                [stem]
                ++++
                sqrt(4) = 2
                ++++
                                
                And inline stem:[[[a,b\\],[c,d\\]\\]((n),(k))] too.
                """, """
                 <div class="sect0" id="_some_formulas">
                  <h1>Some formulas</h1>
                 <div class="sectionbody">
                 <div class="stemblock">
                  <div class="content">
                 \\$sqrt(4) = 2\\$   </div>
                 </div>
                 <div class="paragraph">
                 <p>And inline  \\$[[a,b\\],[c,d\\]\\]((n),(k))\\$  too.</p>
                 </div>
                 </div>
                 </div>
                """);
    }

    @Test
    void xref() {
        assertRenderingContent("xref:foo.adoc[Bar]", " <a href=\"foo.html\">Bar</a>\n");
    }

    @Test
    void embeddedImage(@TempDir final Path work) throws IOException {
        final var base64 = "iVBORw0KGgoAAAANSUhEUgAAACQAAAAkCAYAAADhAJiYAAAAAXNSR0IArs4c6QAAAIRlWElmTU0AKgAAAAgABQESAAMAAAABAAEAAAEaAAUAAAABAAAASgEbAAUAAAABAAAAUgEoAAMAAAABAAIAAIdpAAQAAAABAAAAWgAAAAAAAACWAAAAAQAAAJYAAAABAAOgAQADAAAAAQABAACgAgAEAAAAAQAAACSgAwAEAAAAAQAAACQAAAAAWFiVFgAAAAlwSFlzAAAXEgAAFxIBZ5/SUgAAAVlpVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IlhNUCBDb3JlIDUuNC4wIj4KICAgPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4KICAgICAgPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIKICAgICAgICAgICAgeG1sbnM6dGlmZj0iaHR0cDovL25zLmFkb2JlLmNvbS90aWZmLzEuMC8iPgogICAgICAgICA8dGlmZjpPcmllbnRhdGlvbj4xPC90aWZmOk9yaWVudGF0aW9uPgogICAgICA8L3JkZjpEZXNjcmlwdGlvbj4KICAgPC9yZGY6UkRGPgo8L3g6eG1wbWV0YT4KTMInWQAACC9JREFUWAm1WHuMXFUZ/92Zfczszmu7rlowgCZC20TXpA1afND6QqFbEGlNFF8pUohRREGqS0wLcWspJkI0kbd/NIKLJs7e2ZZX3YKPaG01qCGoaazBUOxj595578zcOf6+c++5Ozu7Lbs1Pcmde+53vu873/t8Z4D/Z2xXkXnkagHYPKRzATDCPFcYhO38nM9+2CdX6K3GVfRcbHlmnlOqSyPY+Sx+qxQO8LGdl2aJlDU7X/xsvskXQyvCrLeayLlXIJbYiLyrUOSTSK+E7X5VszgEX+DF8GvDOTuBRBgZSu1CjPu28AfOJ6G5qe/gKXcZ1lgNGLe2bfh606ULdEh1a6Y59yvoTw2jJrKpbVD1G1CglZLpQdTV3RpnOc5xLBmNxQI59ziel7hxs6HWOWd3EEst2PlhDV9igC/NQkbjRusuJFJDKBYagHVHKFB/egeKzjGk0gxo654Qfk4mRtN9pXcym1qBJe7Ve0mQP3DId6XtfAn7PYVn6grZ/LXh+iKFWpqFhGmzuVtboFh4Fa3GDr1PEn6Ki0tHMg+hUjyIXsoXiYwx2CM6I9XZlYGF9ZitOZ/QmosFxBIyxlVPSLR9yk/1vcXLsa+sMKVr0za9bpIhRD7bidFMtLedl/1Adg5qdibIs/mNDO7PaJg5Omzn8QDXxVMnls/BP4Msr++yw0GBW+1+k4XvElQqzPLo7f4GlmTT1zGQyWIgtQc558ewCJMRYSkouBW6N4V615iGXe5XKj0/qx9jgV9Qw5zjBBo/EfLKls+j1WqYLCpMOB6emaErT60N1yfyO7TbJgtcP/UeDTfJESLNnZzZQssP+4Wtp/u7tE5aa9yk5mZEmjuRTvei5Z1kWB9DN8PJ6tptltF1coxHylEkkgL3y8BLUOH6kiZTQYBOuu/WFpAAzbl+Vgkju7BWw18gfCL/NUy6H8OvOH+uKXjXh3vZ7qc1rB3+QFDtQ6TFTMLgzL+gXTXh/At7VW9ImnN/E7jwCPb+04dPOM8HsKN0VSzEncjPwg0Pkywhkj9Z2GVS5CQ4JXPiyfej4dHk1iiutGY0mZ3/HGL970Wd55iGv92Hw7oNxSLo3gtRcr4d7mVFbg/hnnOnhptkCZFONzGSiyY5WkXOqxw1N2P8lTgD+d+BJaY0WILfuMHOPxqslTFZvdCQkeaRAF5hArxVw40XQiQmZ9vcnz4YpLlXGKWmF6FEjZXlp7lgxJJ38kS/gOeYUPvwVTqkaUaOenMUBafAdO9jB/A9DZOfSH2UAe4SHueXDz9wYP7+IYFMxsf9rBINbKfia5T/SYiTc97GgK0G59hDGm7OMPkw1XjS2aYDPMd2xHY+FNLbzrd0GdhXkTLxQQ03yRMgdUi4KQCrHbRCnGnuIKZuCRkq7EIyFaOmDnqioxp+bLVvGflYDWmOgD+m70HZPYLzU/xos+5IZidK7suI00gRdZfGXbeO9LPn3Nw2c1NQIyxLnMDBZr0WeRy56Z1ANIVo13UBxi5ckTyu42YrO0MzLEvxbItis+WxLNyK/xRXYCTt1yV7+n3cdytR34imlmGlzk6LiSJxq/ej+IaXfhtmWecjjI+f0UoDkKPTKQNes4TevgRmyn/DyMA7NL6462JW6fXrfcsI/RB5mhZXkHKOqHkremJrkWR1EPELbpOwm7Ax80iogGbYKZAAJWO2M+Wffq0f9Z7P0rY3oS85DIverUiAq9f47EF35GF8PPV3zUc0fJIqiGVkZE+cx8p8A0vCFvSlLkCL8taqsvYPWKSt1x7GtW8+7luGVm0bcy1kFoylzPcvT12GaIRHhjWCTNqHOtSS+vP5IXug/Ro46ayBp75MQa7jkZLQ9i/XaJUZD9FoFJ63mrh/0ride2jgQhYKFiC9zXa6YiL/BTL+M930IvYWV8Fr3UwzXY94IsMGTCwGVEu/47uEnt6P0jV0L41RLdW5+ARa1h4G8GNIZ86H6+TIZ2Re7Jk9T/sW6WWIAE9Xlb4ImvuWIZKG3uYJL6f9sw2l21bbmWF1l7Psp9rlBtfO36LLgLS1dmFEg03TZ3CCd0fad6x63r2IUeNp979s6h/Uq1m6xc7/gEF5DS0UoaV6Uat4tEgDsb4edLF1Va0PoBG7nz31ek0zMnAfy8BhxGXN84uiDvzZdDc7z48hcysVTWLxCbYWrL61G+ndF0l0Nzl+GMvSER0fpwpyhh3gs44x1ktXSSwNYzD9Br4B9mdoNn7PxBij2xpoqRwzN8pz7jZsGPi+vhhsXTNbNkgyVyBdD4JaEnf/inj/SlTLJeIdofbD6KOGjAwyfJWke9jk34dIb52Z8wotFUO5dCm6jv8FraFv0IKfZ3ZejBidIDRF3vst602MsUE0ai5mGivxyaFjYVYTRcZcl0nqyujLX4Z+3tOrpSqZJDCQHtbBO02zl92tqDYuYbbcgauHePNQGZ2+EtyK8yt58m/IjKGaXoVqcTPdPYUKdRrMrKI7eautlXiepdHTfZXey9z19Ac6/hDYxFu6jAqopXMUyzIX8Y+EFqYdG1HrRxTi2YCOAc9uQNoRS9GnfMS1URUUSHYEm60qcZ/Uj+1einzhZsbWp5htCRQKNbQiv9a8boQHqd/BmGshKf1SGDcvcxHtXgvH2QKrNYyNA9fgKgojLpXqLO+Duub6bKLdfehnSVdSPWW8RVLe4kHaRVze1dIHsSH9RcJWsBPYAq/+LlzNoip7mUuBT3iaX0FsH1IGOk5lvaHBkTSXnil7gs0zhwjcPjQ9hWsfnXu0ry04FwLfGnOFm4PcsbGsdQrTjr8Inv8D6yTlAwxy6EMAAAAASUVORK5CYII=";
        Files.createDirectories(work);
        Files.write(work.resolve("img.png"), Base64.getDecoder().decode(base64));
        final var doc = new Parser().parseBody("""
                = Test
                                
                image::img.png[logo]
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setAssetsBase(work)
                .setAttributes(Map.of("noheader", "true", "data-uri", "")));
        renderer.visitBody(doc);
        assertEquals("""
                 <div class="sect0" id="_test">
                  <h1>Test</h1>
                 <div class="sectionbody">
                 <div class="imageblock">
                 <div class="content">
                 <img src="data:image/png;base64,$base64" alt="logo">
                 </div>
                 </div>
                 </div>
                 </div>
                """.replace("$base64", base64), renderer.result());
    }

    @Test
    void imageRole() {
        final var doc = new Parser().parseBody("""
                = Test
                                
                image::img.png[logo,.center.w80]
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setAttributes(Map.of("noheader", "true")));
        renderer.visitBody(doc);
        assertEquals("""
                 <div class="sect0" id="_test">
                  <h1>Test</h1>
                 <div class="sectionbody">
                 <div class="imageblock">
                 <div class="content">
                 <img src="img.png" alt="logo" class="center w80">
                 </div>
                 </div>
                 </div>
                 </div>
                """, renderer.result());
    }

    @Test
    void imageUnderscores() {
        final var doc = new Parser().parseBody("""
                = Test
                                
                * image:Apache_Feather_Logo.png[romain_asf,role="w32"] link:https://home.apache.org/committer-index.html#rmannibucau[ASF Member]
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setAttributes(Map.of("noheader", "true")));
        renderer.visitBody(doc);
        assertEquals("""
                 <div class="sect0" id="_test">
                  <h1>Test</h1>
                 <div class="sectionbody">
                 <div class="ulist">
                 <ul>
                  <li>
                 <div class="paragraph">
                 <p> <img src="Apache_Feather_Logo.png" alt="romain_asf" class="w32">
                  <a href="https://home.apache.org/committer-index.html#rmannibucau">ASF Member</a>
                </p>
                 </div>
                  </li>
                 </ul>
                 </div>
                 </div>
                 </div>
                """, renderer.result());
    }

    @Test
    void carbonNowImage() {
        final var doc = new Parser().parseBody("""
                = Test
                                
                [source,carbonNowBaseUrl="auto",alt="image"]
                ----
                public record UserId(String name) {}
                ----
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setAttributes(Map.of("noheader", "true", "data-uri", "false"/*true would mean we depend on the http url at test time, we don't want that*/)));
        renderer.visitBody(doc);
        assertEquals("""
                 <div class="sect0" id="_test">
                  <h1>Test</h1>
                 <div class="sectionbody">
                                
                  <iframe
                    src="https://carbon.now.sh/embed?bg=rgba%28171%2C184%2C195%2C100%29&t=vscode&wt=none&l=text%2Fx-java&width=680&ds=true&dsyoff=20px&dsblur=68px&wc=true&wa=true&pv=48px&ph=32px&ln=true&fl=1&fm=Droid+Sans+Mono&fs=13px&lh=133%25&si=false&es=2x&wm=false&code=public+record+UserId%28String+name%29+%7B%7D%0A"
                    style="width: 1024px; height: 473px; border:0; transform: scale(1); overflow:hidden;"
                    sandbox="allow-scripts allow-same-origin">
                  </iframe>
                 </div>
                 </div>
                """, renderer.result());
    }

    @Test
    void ascii2svg() {
        assertRenderingContent("""
                = Test
                                
                [a2s, format="svg"]
                ....
                .-------------------------.
                |                         |
                | .---.-. .-----. .-----. |
                | | .-. | +-->  | |  <--| |
                | | '-' | |  <--| +-->  | |
                | '---'-' '-----' '-----' |
                |  ascii     2      svg   |
                |                         |
                '-------------------------'
                ....
                """, """
                 <div class="sect0" id="_test">
                  <h1>Test</h1>
                 <div class="sectionbody">
                 <img src="data:image/svg+xml;base64,PCFET0NUWVBFIHN2ZyBQVUJMSUMgIi0vL1czQy8vRFREIFNWRyAxLjEvL0VOIiAiaHR0cDovL3d3dy53My5vcmcvR3JhcGhpY3MvU1ZHLzEuMS9EVEQvc3ZnMTEuZHRkIj4KPHN2ZyB3aWR0aD0iMjUycHgiIGhlaWdodD0iMTYwcHgiIHZlcnNpb249IjEuMSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIiB4bWxuczp4bGluaz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94bGluayI+CjxkZWZzPgogICAgPGZpbHRlciBpZD0iZHNGaWx0ZXIiIHdpZHRoPSIxNTAlIiBoZWlnaHQ9IjE1MCUiPgogICAgICA8ZmVPZmZzZXQgcmVzdWx0PSJvZmZPdXQiIGluPSJTb3VyY2VHcmFwaGljIiBkeD0iMiIgZHk9IjIiLz4KICAgICAgPGZlQ29sb3JNYXRyaXggcmVzdWx0PSJtYXRyaXhPdXQiIGluPSJvZmZPdXQiIHR5cGU9Im1hdHJpeCIgdmFsdWVzPSIwLjIgMCAwIDAgMCAwIDAuMiAwIDAgMCAwIDAgMC4yIDAgMCAwIDAgMCAxIDAiLz4KICAgICAgPGZlR2F1c3NpYW5CbHVyIHJlc3VsdD0iYmx1ck91dCIgaW49Im1hdHJpeE91dCIgc3RkRGV2aWF0aW9uPSIzIi8+CiAgICAgIDxmZUJsZW5kIGluPSJTb3VyY2VHcmFwaGljIiBpbjI9ImJsdXJPdXQiIG1vZGU9Im5vcm1hbCIvPgogICAgPC9maWx0ZXI+CiAgICA8bWFya2VyIGlkPSJpUG9pbnRlciIKICAgICAgdmlld0JveD0iMCAwIDEwIDEwIiByZWZYPSI1IiByZWZZPSI1IgogICAgICBtYXJrZXJVbml0cz0ic3Ryb2tlV2lkdGgiCiAgICAgIG1hcmtlcldpZHRoPSI4IiBtYXJrZXJIZWlnaHQ9IjE1IgogICAgb3JpZW50PSJhdXRvIj4KICAgIDxwYXRoIGQ9Ik0gMTAgMCBMIDEwIDEwIEwgMCA1IHoiIC8+CiAgPC9tYXJrZXI+CiAgPG1hcmtlciBpZD0iUG9pbnRlciIKICAgIHZpZXdCb3g9IjAgMCAxMCAxMCIgcmVmWD0iNSIgcmVmWT0iNSIKICAgIG1hcmtlclVuaXRzPSJzdHJva2VXaWR0aCIKICAgIG1hcmtlcldpZHRoPSI4IiBtYXJrZXJIZWlnaHQ9IjE1IgogICAgb3JpZW50PSJhdXRvIj4KICAgIDxwYXRoIGQ9Ik0gMCAwIEwgMTAgNSBMIDAgMTAgeiIgLz4KICA8L21hcmtlcj4KPC9kZWZzPiAgPGcgaWQ9ImNsb3NlZCIgc3Ryb2tlPSIjMDAwIiBzdHJva2Utd2lkdGg9IjIiIGZpbGw9Im5vbmUiPgogICAgPHBhdGggaWQ9ImNsb3NlZDAiIGZpbGw9IiNmZmYiIGQ9Ik0gNC41IDE4LjAgUSA0LjUgOC4wIDE0LjUgOC4wIEwgMTMuNSA4LjAgIEwgMjIuNSA4LjAgIEwgMzEuNSA4LjAgIEwgNDAuNSA4LjAgIEwgNDkuNSA4LjAgIEwgNTguNSA4LjAgIEwgNjcuNSA4LjAgIEwgNzYuNSA4LjAgIEwgODUuNSA4LjAgIEwgOTQuNSA4LjAgIEwgMTAzLjUgOC4wICBMIDExMi41IDguMCAgTCAxMjEuNSA4LjAgIEwgMTMwLjUgOC4wICBMIDEzOS41IDguMCAgTCAxNDguNSA4LjAgIEwgMTU3LjUgOC4wICBMIDE2Ni41IDguMCAgTCAxNzUuNSA4LjAgIEwgMTg0LjUgOC4wICBMIDE5My41IDguMCAgTCAyMDIuNSA4LjAgIEwgMjExLjUgOC4wICBMIDIyMC41IDguMCAgTCAyMjkuNSA4LjAgIEwgMjI4LjUgOC4wIFEgMjM4LjUgOC4wIDIzOC41IDE4LjAgIEwgMjM4LjUgMjQuMCAgTCAyMzguNSA0MC4wICBMIDIzOC41IDU2LjAgIEwgMjM4LjUgNzIuMCAgTCAyMzguNSA4OC4wICBMIDIzOC41IDEwNC4wICBMIDIzOC41IDEyMC4wICBMIDIzOC41IDEyNi4wIFEgMjM4LjUgMTM2LjAgMjI4LjUgMTM2LjAgIEwgMjI5LjUgMTM2LjAgIEwgMjIwLjUgMTM2LjAgIEwgMjExLjUgMTM2LjAgIEwgMjAyLjUgMTM2LjAgIEwgMTkzLjUgMTM2LjAgIEwgMTg0LjUgMTM2LjAgIEwgMTc1LjUgMTM2LjAgIEwgMTY2LjUgMTM2LjAgIEwgMTU3LjUgMTM2LjAgIEwgMTQ4LjUgMTM2LjAgIEwgMTM5LjUgMTM2LjAgIEwgMTMwLjUgMTM2LjAgIEwgMTIxLjUgMTM2LjAgIEwgMTEyLjUgMTM2LjAgIEwgMTAzLjUgMTM2LjAgIEwgOTQuNSAxMzYuMCAgTCA4NS41IDEzNi4wICBMIDc2LjUgMTM2LjAgIEwgNjcuNSAxMzYuMCAgTCA1OC41IDEzNi4wICBMIDQ5LjUgMTM2LjAgIEwgNDAuNSAxMzYuMCAgTCAzMS41IDEzNi4wICBMIDIyLjUgMTM2LjAgIEwgMTMuNSAxMzYuMCAgTCAxNC41IDEzNi4wIFEgNC41IDEzNi4wIDQuNSAxMjYuMCAgTCA0LjUgMTIwLjAgIEwgNC41IDEwNC4wICBMIDQuNSA4OC4wICBMIDQuNSA3Mi4wICBMIDQuNSA1Ni4wICBMIDQuNSA0MC4wICBMIDQuNSAyNC4wIFoiIC8+CiAgICA8cGF0aCBpZD0iY2xvc2VkMSIgZmlsbD0iI2ZmZiIgZD0iTSAyMi41IDUwLjAgUSAyMi41IDQwLjAgMzIuNSA0MC4wIEwgMzEuNSA0MC4wICBMIDQwLjUgNDAuMCAgTCA0OS41IDQwLjAgIEwgNTguNSA0MC4wICBMIDY3LjUgNDAuMCAgTCA2Ni41IDQwLjAgUSA3Ni41IDQwLjAgNzYuNSA1MC4wICBMIDc2LjUgNTYuMCAgTCA3Ni41IDcyLjAgIEwgNzYuNSA3OC4wIFEgNzYuNSA4OC4wIDY2LjUgODguMCAgTCA2Ny41IDg4LjAgIEwgNTguNSA4OC4wICBMIDQ5LjUgODguMCAgTCA0MC41IDg4LjAgIEwgMzEuNSA4OC4wICBMIDMyLjUgODguMCBRIDIyLjUgODguMCAyMi41IDc4LjAgIEwgMjIuNSA3Mi4wICBMIDIyLjUgNTYuMCBaIiAvPgogICAgPHBhdGggaWQ9ImNsb3NlZDMiIGZpbGw9IiNmZmYiIGQ9Ik0gOTQuNSA1MC4wIFEgOTQuNSA0MC4wIDEwNC41IDQwLjAgTCAxMDMuNSA0MC4wICBMIDExMi41IDQwLjAgIEwgMTIxLjUgNDAuMCAgTCAxMzAuNSA0MC4wICBMIDEzOS41IDQwLjAgIEwgMTM4LjUgNDAuMCBRIDE0OC41IDQwLjAgMTQ4LjUgNTAuMCAgTCAxNDguNSA1Ni4wICBMIDE0OC41IDcyLjAgIEwgMTQ4LjUgNzguMCBRIDE0OC41IDg4LjAgMTM4LjUgODguMCAgTCAxMzkuNSA4OC4wICBMIDEzMC41IDg4LjAgIEwgMTIxLjUgODguMCAgTCAxMTIuNSA4OC4wICBMIDEwMy41IDg4LjAgIEwgMTA0LjUgODguMCBRIDk0LjUgODguMCA5NC41IDc4LjAgIEwgOTQuNSA3Mi4wICBMIDk0LjUgNTYuMCBaIiAvPgogICAgPHBhdGggaWQ9ImNsb3NlZDUiIGZpbGw9IiNmZmYiIGQ9Ik0gMTY2LjUgNTAuMCBRIDE2Ni41IDQwLjAgMTc2LjUgNDAuMCBMIDE3NS41IDQwLjAgIEwgMTg0LjUgNDAuMCAgTCAxOTMuNSA0MC4wICBMIDIwMi41IDQwLjAgIEwgMjExLjUgNDAuMCAgTCAyMTAuNSA0MC4wIFEgMjIwLjUgNDAuMCAyMjAuNSA1MC4wICBMIDIyMC41IDU2LjAgIEwgMjIwLjUgNzIuMCAgTCAyMjAuNSA3OC4wIFEgMjIwLjUgODguMCAyMTAuNSA4OC4wICBMIDIxMS41IDg4LjAgIEwgMjAyLjUgODguMCAgTCAxOTMuNSA4OC4wICBMIDE4NC41IDg4LjAgIEwgMTc1LjUgODguMCAgTCAxNzYuNSA4OC4wIFEgMTY2LjUgODguMCAxNjYuNSA3OC4wICBMIDE2Ni41IDcyLjAgIEwgMTY2LjUgNTYuMCBaIiAvPgogIDwvZz4KICA8ZyBpZD0ibGluZXMiIHN0cm9rZT0iIzAwMCIgc3Ryb2tlLXdpZHRoPSIyIiBmaWxsPSJub25lIj4KICAgIDxwYXRoIGlkPSJvcGVuMiIgZD0iTSAyMi41IDUwLjAgUSAyMi41IDQwLjAgMzIuNSA0MC4wIEwgMzEuNSA0MC4wICBMIDQwLjUgNDAuMCAgTCA0OS41IDQwLjAgIEwgNTguNSA0MC4wICBMIDY3LjUgNDAuMCAgTCA2Ni41IDQwLjAgUSA3Ni41IDQwLjAgNzYuNSA1MC4wICBMIDc2LjUgNTYuMCAgTCA3Ni41IDcyLjAgIEwgNzYuNSA3OC4wIFEgNzYuNSA4OC4wIDY2LjUgODguMCAgTCA2Ny41IDg4LjAgIEwgNjguNSA4OC4wIFEgNTguNSA4OC4wIDU4LjUgNzguMCAgTCA1OC41IDgyLjAgUSA1OC41IDcyLjAgNDguNSA3Mi4wICBMIDQ5LjUgNzIuMCAgTCA1MC41IDcyLjAgUSA0MC41IDcyLjAgNDAuNSA2Mi4wICBMIDQwLjUgNjYuMCBRIDQwLjUgNTYuMCA1MC41IDU2LjAgIEwgNDkuNSA1Ni4wICBMIDQ4LjUgNTYuMCBRIDU4LjUgNTYuMCA1OC41IDQ2LjAgIiAvPgogICAgPHBhdGggaWQ9Im9wZW40IiBtYXJrZXItZW5kPSJ1cmwoI1BvaW50ZXIpIiAgZD0iTSAxNjYuNSA1MC4wIFEgMTY2LjUgNDAuMCAxNzYuNSA0MC4wIEwgMTc1LjUgNDAuMCAgTCAxODQuNSA0MC4wICBMIDE5My41IDQwLjAgIEwgMjAyLjUgNDAuMCAgTCAyMTEuNSA0MC4wICBMIDIxMC41IDQwLjAgUSAyMjAuNSA0MC4wIDIyMC41IDUwLjAgIEwgMjIwLjUgNTYuMCAgTCAyMjAuNSA3Mi4wICBMIDIyMC41IDc4LjAgUSAyMjAuNSA4OC4wIDIxMC41IDg4LjAgIEwgMjExLjUgODguMCAgTCAyMDIuNSA4OC4wICBMIDE5My41IDg4LjAgIEwgMTg0LjUgODguMCAgTCAxNzUuNSA4OC4wICBMIDE3Ni41IDg4LjAgUSAxNjYuNSA4OC4wIDE2Ni41IDc4LjAgIEwgMTY2LjUgNzIuMCAgTCAxNzUuNSA3Mi4wICBMIDE4NC41IDcyLjAgIEwgMTkzLjUgNzIuMCAiIC8+CiAgICA8cGF0aCBpZD0ib3BlbjYiIG1hcmtlci1lbmQ9InVybCgjUG9pbnRlcikiICBkPSJNIDk0LjUgNTYuMCBMIDEwMy41IDU2LjAgIEwgMTEyLjUgNTYuMCAgTCAxMjEuNSA1Ni4wICIgLz4KICAgIDxwYXRoIGlkPSJvcGVuNyIgbWFya2VyLXN0YXJ0PSJ1cmwoI2lQb2ludGVyKSIgIGQ9Ik0gMTkzLjUgNTYuMCBMIDIwMi41IDU2LjAgIEwgMjExLjUgNTYuMCAiIC8+CiAgICA8cGF0aCBpZD0ib3BlbjgiIG1hcmtlci1zdGFydD0idXJsKCNpUG9pbnRlcikiICBkPSJNIDEyMS41IDcyLjAgTCAxMzAuNSA3Mi4wICBMIDEzOS41IDcyLjAgIiAvPgogIDwvZz4KICA8ZyBpZD0idGV4dCIgc3Ryb2tlPSJub25lIiBzdHlsZT0iZm9udC1mYW1pbHk6Q29uc29sYXMsTW9uYWNvLEFub255bW91cyBQcm8sQW5vbnltb3VzLEJpdHN0cmVhbSBTYW5zIE1vbm8sbW9ub3NwYWNlO2ZvbnQtc2l6ZToxNS4ycHgiID4KICAgIDx0ZXh0IGlkPSJvYmo5IiB4PSIzMS41IiB5PSIxMDQuMCIgZmlsbD0iIzAwMCI+YXNjaWk8L3RleHQ+CiAgICA8dGV4dCBpZD0ib2JqMTAiIHg9IjEyMS41IiB5PSIxMDQuMCIgZmlsbD0iIzAwMCI+MjwvdGV4dD4KICAgIDx0ZXh0IGlkPSJvYmoxMSIgeD0iMTg0LjUiIHk9IjEwNC4wIiBmaWxsPSIjMDAwIj5zdmc8L3RleHQ+CiAgPC9nPgo8L3N2Zz4K" alt="a2s">
                 </div>
                 </div>
                """);
    }

    @Test
    void icon() {
        assertRenderingContent("icon:fas fa-gift[]", "<span class=\"icon\"><i class=\"fas fa-gift\"></i></span>");
    }

    @Test
    void codeInSectionTitle() {
        assertRenderingContent("== Section `#1`", """
                 <div class="sect1" id="_section_1">
                  <h2>Section <code>#1</code></h2>
                 <div class="sectionbody">
                 </div>
                 </div>
                """);
    }

    @Test
    void codeInSectionTitleComplex() {
        assertRenderingContent("""
                == Title :: foo `bar.json`
                                
                foo""", """
                 <div class="sect1" id="_title__foo_barjson">
                  <h2>Title :: foo <code>bar.json</code></h2>
                 <div class="sectionbody">
                 <div class="paragraph">
                 <p>
                foo
                 </p>
                 </div>
                 </div>
                 </div>
                """);
    }

    @Test
    void linkWithImage() {
        assertRenderingContent(
                "link:http://foo.bar[this is image:foo.png[alt]]",
                """
                         <a href="http://foo.bar">this is  <img src="foo.png" alt="alt">
                        </a>
                        """);
    }

    @Test
    void callouts() {
        assertRenderingContent("""
                [source,properties]
                ----
                prefix.version = 1.2.3 <1>
                ----
                <.> Version of the tool to install, using `relaxed` option it can be a version prefix (`21.` for ex),""",
                """
                         <div class="listingblock">
                         <div class="content">
                         <pre class="highlightjs highlight"><code class="language-properties hljs" data-lang="properties">prefix.version = 1.2.3 <b class="conum">(1)</b></code></pre>
                         </div>
                         </div>
                         <div class="colist arabic">
                          <ol>
                           <li>
                         <span>
                        Version of the tool to install, using\s
                         </span>
                        <code>relaxed</code> <span>
                         option it can be a version prefix (
                         </span>
                        <code>21.</code> <span>
                         for ex),
                         </span>
                           </li>
                          </ol>
                         </div>
                        """);
    }

    private void assertRendering(final String adoc, final String html) {
        final var doc = new Parser().parse(adoc, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new AsciidoctorLikeHtmlRenderer();
        renderer.visit(doc);
        assertEquals(html, renderer.result());
    }

    private void assertRenderingContent(final String adoc, final String html) {
        final var doc = new Parser().parseBody(adoc, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration().setAttributes(Map.of("noheader", "true")));
        renderer.visitBody(doc);
        assertEquals(html, renderer.result());
    }
}
