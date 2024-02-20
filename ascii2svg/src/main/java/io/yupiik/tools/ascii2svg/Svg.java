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
package io.yupiik.tools.ascii2svg;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.yupiik.tools.ascii2svg.Canvas.OBJ_TAG_RE;
import static io.yupiik.tools.ascii2svg.Point.Hint.END_MARKER;
import static io.yupiik.tools.ascii2svg.Point.Hint.ROUNDED_CORNER;
import static io.yupiik.tools.ascii2svg.Point.Hint.START_MARKER;
import static java.util.stream.Collectors.joining;

public class Svg {
    public String convert(final String text, final int tabWidth, final boolean noBlur, final String font, final int scaleX, final int scaleY) {
        return convert(Canvas.newInstance(text, tabWidth, noBlur), noBlur, font, scaleX, scaleY);
    }

    public String convert(final Canvas c, final boolean noBlur, final String font, final int scaleX, final int scaleY) {
        final var result = new StringBuilder()
                .append("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n")
                .append("<svg width=\"").append((c.size()[0] + 1) * scaleX).append("px\" height=\"").append((c.size()[1] + 1) * scaleY).append("px\" ")
                .append("version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">\n")
                .append("<defs>\n" +
                        "    <filter id=\"dsFilter\" width=\"150%\" height=\"150%\">\n" +
                        "      <feOffset result=\"offOut\" in=\"SourceGraphic\" dx=\"2\" dy=\"2\"/>\n" +
                        "      <feColorMatrix result=\"matrixOut\" in=\"offOut\" type=\"matrix\" values=\"0.2 0 0 0 0 0 0.2 0 0 0 0 0 0.2 0 0 0 0 0 1 0\"/>\n" +
                        "      <feGaussianBlur result=\"blurOut\" in=\"matrixOut\" stdDeviation=\"3\"/>\n" +
                        "      <feBlend in=\"SourceGraphic\" in2=\"blurOut\" mode=\"normal\"/>\n" +
                        "    </filter>\n" +
                        "    <marker id=\"iPointer\"\n" +
                        "      viewBox=\"0 0 10 10\" refX=\"5\" refY=\"5\"\n" +
                        "      markerUnits=\"strokeWidth\"\n" +
                        "      markerWidth=\"")
                .append(scaleX - 1).append("\" markerHeight=\"").append(scaleY - 1).append("\"\n")
                .append("    orient=\"auto\">\n").append("    <path d=\"M 10 0 L 10 10 L 0 5 z\" />\n").append("  </marker>\n")
                .append("  <marker id=\"Pointer\"\n").append("    viewBox=\"0 0 10 10\" refX=\"5\" refY=\"5\"\n")
                .append("    markerUnits=\"strokeWidth\"\n").append("    markerWidth=\"").append(scaleX - 1)
                .append("\" markerHeight=\"").append(scaleY - 1).append("\"\n")
                .append("    orient=\"auto\">\n").append("    <path d=\"M 0 0 L 10 5 L 0 10 z\" />\n")
                .append("  </marker>\n")
                .append("</defs>");

        final Function<String, String> getOpts = tag -> {
            final var value = c.options().get(tag);
            if (value == null) {
                return "";
            }
            if (value instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked") final var casted = (Map<String, ?>) value;
                return casted.entrySet().stream()
                        .filter(Predicate.not(it -> it.getKey().startsWith("a2s:")))
                        .map(it -> it.getKey() + "=\"" + it.getValue() + '"')
                        .collect(joining(" "));
            }
            return value.toString();
        };

        if (noBlur) {
            result.append("  <g id=\"closed\" stroke=\"#000\" stroke-width=\"2\" fill=\"none\">\n");
        } else {
            result.append("  <g id=\"closed\" filter=\"url(#dsFilter)\" stroke=\"#000\" stroke-width=\"2\" fill=\"none\">\n");
        }

        int i = 0;
        for (final var obj : c.objects().value()) {
            if (obj.isClosed() && !obj.isText()) {
                var opts = "";
                if (obj.isDashed()) {
                    opts = "stroke-dasharray=\"5 5\" ";
                }

                var tag = obj.tag();
                if (tag == null || tag.isEmpty()) {
                    tag = "__a2s__closed__options__";
                }
                opts += getOpts.apply(tag);

                var startLink = "";
                var endLink = "";
                final var link = c.options().get("a2s:link");
                if (link != null) {
                    final var s = link.toString();
                    if (!s.isBlank()) {
                        startLink = "<a href=\"" + s + "\">";
                        endLink = "</a>";
                    }
                }

                result.append("    ").append(startLink).append("<path id=\"closed").append(i).append("\" ").append(!opts.isEmpty() ? opts + ' ' : "").append("d=\"").append(flatten(obj.points(), scaleX, scaleY)).append("Z\" />").append(endLink).append("\n");
            }
            i++;
        }
        result.append("  </g>\n");
        result.append("  <g id=\"lines\" stroke=\"#000\" stroke-width=\"2\" fill=\"none\">\n");

        i = 0;
        for (final var obj : c.objects().value()) {
            try {
                if (!obj.isClosed() && !obj.isText()) {
                    final var points = obj.points();

                    var opts = "";
                    if (obj.isDashed()) {
                        opts += "stroke-dasharray=\"5 5\" ";
                    }
                    if (points[0].hint() == START_MARKER) {
                        opts += "marker-start=\"url(#iPointer)\" ";
                    }
                    if (points[points.length - 1].hint() == END_MARKER) {
                        opts += "marker-end=\"url(#Pointer)\" ";
                    }

                    for (final var p : points) {
                        if (p.hint() == null) {
                            continue;
                        }
                        switch (p.hint()) {
                            case DOT: {
                                final var sp = scale(p, scaleX, scaleY);
                                result.append("    <circle cx=\"").append(sp[0]).append("\" cy=\"").append(sp[1]).append("\" r=\"3\" fill=\"#000\" />\n");
                                break;
                            }
                            case TICK: {
                                final var sp = scale(p, scaleX, scaleY);
                                result
                                        .append("    <line x1=\"").append(sp[0] - 4).append("\" y1=\"").append(sp[1] - 4).append("\" x2=\"").append(sp[0] + 4).append("\" y2=\"").append(sp[1] + 4).append("\" stroke-width=\"1\" />\n")
                                        .append("    <line x1=\"").append(sp[0] + 4).append("\" y1=\"").append(sp[1] - 4).append("\" x2=\"").append(sp[0] - 4).append("\" y2=\"").append(sp[1] + 4).append("\" stroke-width=\"1\" />\n");
                                break;
                            }
                            default:
                        }
                    }

                    opts += getOpts.apply(obj.tag());

                    var startLink = "";
                    var endLink = "";
                    final var link = c.options().get("a2s:link");
                    if (link != null) {
                        final var s = link.toString();
                        if (!s.isBlank()) {
                            startLink = "<a href=\"" + s + "\">";
                            endLink = "</a>";
                        }
                    }

                    result.append("    ").append(startLink).append("<path id=\"open").append(i).append("\" ").append(!opts.isEmpty() ? opts + ' ' : "").append("d=\"").append(flatten(obj.points(), scaleX, scaleY)).append("\" />").append(endLink).append("\n");
                }
            } finally {
                i++;
            }
        }
        result.append("  </g>\n");
        result.append("  <g id=\"text\" stroke=\"none\" style=\"font-family:").append(font).append(";font-size:15.2px\" >\n");

        final Function<Object, String> findTextColor = o -> {
            final var matcher = OBJ_TAG_RE.matcher(o.tag());
            if (matcher.matches()) {
                final var value = c.options().get(o.tag());
                if (value != null) {
                    final var s = value.toString();
                    if (!s.isBlank()) {
                        return s;
                    }
                }
            }

            final var containers = c.enclosingObjects(c.objects().value(), o.points()[0]);
            if (containers != null && containers.value() != null) {
                for (final var container : containers.value()) {
                    final var value = c.options().get(container.tag());
                    if (value != null) {
                        final var v = value instanceof Map ?
                                ((Map<String, String>) value).getOrDefault("fill", "none") :
                                value.toString();
                        if (!"none".equals(v) && !v.isBlank()) {
                            return new Color(v).textColor();
                        }
                    }
                }
            }
            return "#000";
        };

        i = 0;
        for (final var obj : c.objects().value()) {
            try {
                if (obj.isText() && !obj.isTagDefinition()) {
                    final var color = findTextColor.apply(obj);
                    var startLink = "";
                    var endLink = "";
                    var text = new String(obj.text());
                    var tag = obj.tag();
                    if (!tag.isEmpty()) {
                        final var label = c.options().get("a2s:label");
                        if (label != null) {
                            text = label.toString();
                        }

                        if (obj.corners()[0].x() == 0) {
                            final var opt = c.options().get("a2s:delref");
                            if (opt != null) {
                                continue;
                            }
                        }

                        final var link = c.options().get("a2s:link");
                        if (link != null) {
                            startLink = "<a href=\"" + link + "\">";
                            endLink = "</a>";
                        }
                    }
                    final var sp = scale(obj.points()[0], scaleX, scaleY);
                    // todo: escape text
                    result.append("    ").append(startLink).append("<text id=\"obj").append(i).append("\" x=\"").append(sp[0]).append("\" y=\"").append(sp[1]).append("\" fill=\"").append(color).append("\">").append(text.replace("\"", "&#34;")).append("</text>").append(endLink).append("\n");
                }
            } finally {
                i++;
            }
        }
        result.append("  </g>\n");
        result.append("</svg>\n");
        return result.toString();
    }

    private float[] scale(final Point p, final int scaleX, final int scaleY) {
        return new float[]{scaleX * (.5f + p.x()), scaleY * (.5f + p.y())};
    }

    private StringBuilder flatten(final Point[] points, final int scaleX, final int scaleY) {
        final var out = new StringBuilder();
        final var sp = scale(points[0], scaleX, scaleY);
        var pp = sp;

        int i = 0;
        for (final var cp : points) {
            try {
                final var p = scale(cp, scaleX, scaleY);
                if (i == 0) {
                    if (cp.hint() == ROUNDED_CORNER) {
                        out
                                .append("M ").append(p[0]).append(" ").append(p[1] + 10)
                                .append(" Q ").append(p[0]).append(" ").append(p[1])
                                .append(' ').append(p[0] + 10).append(" ").append(p[1]);
                        continue;
                    }
                    out.append("M ").append(p[0]).append(" ").append(p[1]);
                    continue;
                }

                if (cp.hint() == ROUNDED_CORNER) {
                    float cx = p[0];
                    float cy = p[1];
                    float sx = 0.f;
                    float sy = 0.f;
                    float ex = 0.f;
                    float ey = 0.f;
                    final var np = i == points.length - 1 ? sp : scale(points[i + 1], scaleX, scaleY);
                    if (pp[0] == p[0]) {
                        sx = p[0];
                        if (pp[1] < p[1]) {
                            sy = p[1] - 10;
                        } else {
                            sy = p[1] + 10;
                        }

                        ey = p[1];
                        if (np[0] < p[0]) {
                            ex = p[0] - 10;
                        } else {
                            ex = p[0] + 10;
                        }
                    } else if (pp[1] == p[1]) {
                        sy = p[1];
                        if (pp[0] < p[0]) {
                            sx = p[0] - 10;
                        } else {
                            sx = p[0] + 10;
                        }
                        ex = p[0];
                        if (np[1] <= p[1]) {
                            ey = p[1] - 10;
                        } else {
                            ey = p[1] + 10;
                        }
                    }

                    out
                            .append(" L ").append(sx).append(' ').append(sy)
                            .append(" Q ").append(cx).append(' ').append(cy)
                            .append(' ').append(ex).append(' ').append(ey).append(' ');
                } else {
                    out.append(" L ").append(p[0]).append(' ').append(p[1]).append(' ');
                }

                pp = p;
            } finally {
                i++;
            }
        }

        return out;
    }
}
