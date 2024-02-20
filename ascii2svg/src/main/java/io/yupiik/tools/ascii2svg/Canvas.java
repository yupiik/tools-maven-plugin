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

import io.yupiik.tools.ascii2svg.json.BufferProvider;
import io.yupiik.tools.ascii2svg.json.JsonParser;
import io.yupiik.tools.ascii2svg.json.ObjectJsonCodec;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;


@Data
@Accessors(fluent = true)
public class Canvas {
    static final BufferProvider BUFFER_PROVIDER = new BufferProvider(8096, 8);
    static final Pattern OBJ_TAG_RE = Pattern.compile("(\\d+)\\s*,\\s*(\\d+)$");

    private final char[] grid;
    private final int[] size; // {x,y}
    private final boolean[] visited;
    private final Object.List objects;
    private final Map<String, java.lang.Object> options;

    static Canvas newInstance(final String data, final int tabWidth, final boolean noBlur) {
        final var options = Map.of(
                "__a2s__closed__options__", noBlur ?
                        Map.of("fill", "#fff") :
                        Map.of(
                                "fill", "#fff",
                                "filter", "url(#dsFilter)"));
        final var lines = Stream.of(data.split("\n"))
                .map(it -> it.replace("\n", IntStream.range(0, tabWidth).mapToObj(i -> " ").collect(joining())))
                .toArray(String[]::new);
        final var size = new int[]{Stream.of(lines).mapToInt(String::length).max().orElse(0), lines.length};
        final var grid = new char[size[0] * size[1]];
        final var visited = new boolean[size[0] * size[1]];
        int y = 0;
        for (final var line : lines) {
            final var padding = y * size[0];
            for (int x = 0; x < line.length(); x++) {
                grid[padding + x] = line.charAt(x);
            }
            if (line.length() < size[1]) {
                for (int x = line.length(); x < size[1]; x++) {
                    grid[padding + x] = ' ';
                }
            }
            y++;
        }

        final var from = new Canvas(grid, size, visited, new Object.List(new Object[0]), new HashMap<>(options));
        final var found = from.findObjects();
        return new Canvas(from.grid(), from.size(), from.visited(), new Object.List(found), from.options());
    }

    private boolean isVisited(final int x, final int y) {
        return visited[y * size[0] + x];
    }

    private void visit(final int x, final int y) {
        visited[y * size[0] + x] = true;
    }

    private void unvisit(final int x, final int y) {
        final var idx = y * size[0] + x;
        if (!visited[idx]) {
            throw new IllegalStateException("Can't unvisit a cell you didn't visit: #" + idx);
        }
        visited[idx] = false;
    }

    private boolean canLeft(final int x) {
        return x > 0;
    }

    private boolean canRight(final int x) {
        return x < size[0] - 1;
    }

    private boolean canUp(final int y) {
        return y > 0;
    }

    private boolean canDown(final int y) {
        return y < size[1] - 1;
    }

    private boolean canDiagonal(final int x, final int y) {
        return (canLeft(x) || canRight(x)) && (canUp(y) || canDown(y));
    }

    private Point[] next(final Point pos) {
        if (!isVisited(pos.x(), pos.y())) {
            throw new IllegalStateException("internal error; revisiting " + pos);
        }

        final var out = new AtomicReference<Point[]>();
        final var ch = at(pos);
        if (ch.canHorizontal()) {
            final Consumer<Point> nextHorizontal = p -> {
                if (!isVisited(p.x(), p.y()) && at(p).canHorizontal()) {
                    final var current = out.get();
                    out.set(append(current == null ? new Point[0] : current, p));
                }
            };
            if (canLeft(pos.x())) {
                nextHorizontal.accept(new Point(pos.x() - 1, pos.y(), pos.hint()));
            }
            if (canRight(pos.x())) {
                nextHorizontal.accept(new Point(pos.x() + 1, pos.y(), pos.hint()));
            }
        }
        if (ch.canVertical()) {
            final Consumer<Point> nextVertical = p -> {
                if (!isVisited(p.x(), p.y()) && at(p).canVertical()) {
                    final var current = out.get();
                    out.set(append(current == null ? new Point[0] : current, p));
                }
            };
            if (canUp(pos.y())) {
                nextVertical.accept(new Point(pos.x(), pos.y() - 1, pos.hint()));
            }
            if (canDown(pos.y())) {
                nextVertical.accept(new Point(pos.x(), pos.y() + 1, pos.hint()));
            }
        }
        if (canDiagonal(pos.x(), pos.y())) {
            final BiConsumer<Point, Point> nextDiagonal = (from, to) -> {
                if (!isVisited(to.x(), to.y()) && at(to).canDiagonalFrom(at(from))) {
                    final var current = out.get();
                    out.set(append(current == null ? new Point[0] : current, to));
                }
            };
            if (canUp(pos.y())) {
                if (canLeft(pos.x())) {
                    nextDiagonal.accept(pos, new Point(pos.x() - 1, pos.y() - 1, pos.hint()));
                }
                if (canRight(pos.x())) {
                    nextDiagonal.accept(pos, new Point(pos.x() + 1, pos.y() - 1, pos.hint()));
                }
            }
            if (canDown(pos.y())) {
                if (canLeft(pos.x())) {
                    nextDiagonal.accept(pos, new Point(pos.x() - 1, pos.y() + 1, pos.hint()));
                }
                if (canRight(pos.x())) {
                    nextDiagonal.accept(pos, new Point(pos.x() + 1, pos.y() + 1, pos.hint()));
                }
            }
        }

        return out.get();
    }

    private Object.List scanPath(final Point[] points) {
        var cur = points[points.length - 1];
        var next = next(cur);
        if (next == null || next.length == 0) {
            if (points.length == 1) {
                unvisit(cur.x(), cur.y());
                return null;
            }
            final var o = new Object(points, null, false, false, false, false, null, null)
                    .seal(this);
            return new Object.List(new Object[]{o});
        }

        if (cur.x() == points[0].x() && cur.y() == points[0].y() + 1) {
            var out = new Object[]{
                    new Object(points, null, false, false, false, false, null, null)
                            .seal(this)
            };
            final var list = scanPath(new Point[]{cur});
            if (list != null) {
                for (final var it : list.value()) {
                    out = append(out, it);
                }
            }
            return new Object.List(out);
        }

        Object[] objs = null;
        for (final var n : next) {
            if (isVisited(n.x(), n.y())) {
                continue;
            }
            visit(n.x(), n.y());
            final var p2 = new Point[points.length + 1];
            System.arraycopy(points, 0, p2, 0, points.length);
            p2[p2.length - 1] = n;
            final var list = scanPath(p2);
            if (list != null) {
                for (final var it : list.value()) {
                    objs = append(objs == null ? new Object[0] : objs, it);
                }
            }
        }
        return objs == null ? null : new Object.List(objs);
    }

    public Object.List enclosingObjects(final Object[] objects, final Point p) {
        final var maxTL = new int[]{-1, -1};

        Object[] q = null;
        for (final var o : objects) {
            if (!o.isClosed()) {
                continue;
            }

            if (o.hasPoint(p) && o.corners()[0].x() > maxTL[0] && o.corners()[0].y() > maxTL[1]) {
                q = append(q == null ? new Object[0] : q, o);
                maxTL[0] = o.corners()[0].x();
                maxTL[1] = o.corners()[0].y();
            }
        }

        return q == null ? null : new Object.List(q);
    }

    private Object scanText(final Object[] objects, final int x, final int y) {
        var points = new ArrayList<Point>();
        points.add(new Point(x, y, null));

        int whiteSpaceStreak = 0;
        int[] cur = new int[]{x, y};

        int tagged = 0;
        var tag = new char[0];
        var tagDef = new char[0];

        while (canRight(cur[0])) {
            if (cur[0] == x && at(cur[0], cur[1]).isObjectStartTag()) {
                tagged++;
            } else if (cur[0] > x && at(cur[0], cur[1]).isObjectEndTag()) {
                tagged++;
            }

            cur[0]++;
            if (isVisited(cur[0], cur[1]) && (tagDef.length == 0 || tagDef[tagDef.length - 1] == '}')) {
                break;
            }
            final var ch = at(cur[0], cur[1]);
            if (!ch.isTextCont()) {
                break;
            }
            if (tagged == 0 && ch.isSpace()) {
                whiteSpaceStreak++;
                if (whiteSpaceStreak > 2) {
                    break;
                }
            } else {
                whiteSpaceStreak = 0;
            }

            switch (tagged) {
                case 1:
                    if (!at(cur[0], cur[1]).isObjectEndTag()) {
                        tag = append(tag, ch.value());
                    }
                    break;
                case 2:
                    if (at(cur[0], cur[1]).isTagDefinitionSeparator()) {
                        tagged++;
                    } else {
                        tagged = -1;
                    }
                    break;
                case 3:
                    tagDef = append(tagDef, ch.value());
                    break;
                default:
            }

            points.add(new Point(cur[0], cur[1], null));
        }

        // If we found a start and end tag marker, we either need to assign the tag to the object,
        // or we need to assign the specified options to the global canvas option space.
        if (tagged == 2 || (tagged < 0 && tag.length > 0)) {
            final var t = new String(tag);
            final var container = enclosingObjects(objects, new Point(x, y, null));
            if (container != null && container.value().length > 0) {
                final var from = container.value()[0];
                final var idx = List.of(objects).indexOf(from);
                objects[idx] = new Object(
                        from.points(), from.corners(), from.isText(), from.isTagDefinition(), from.isClosed(), from.isDashed(), from.text(), t);
            }
        } else if (tagged == 3) {
            final var t = new String(tag);

            final var matcher = OBJ_TAG_RE.matcher(t);
            if (matcher.matches()) {
                final var targetX = Integer.parseInt(matcher.group(1), 10);
                final var targetY = Integer.parseInt(matcher.group(2), 10);
                int idx = 0;
                for (final var o : objects) {
                    final var corner = o.corners()[0];
                    if (corner.x() == targetX && corner.y() == targetY) {
                        objects[idx] = new Object(
                                o.points(), o.corners(), o.isText(), o.isTagDefinition(), o.isClosed(), o.isDashed(), o.text(), new String(tag));
                        break;
                    }
                    idx++;
                }
            }

            final var jsonValue = new String(tagDef).strip();
            try (final var json = new JsonParser(new StringReader(jsonValue), BUFFER_PROVIDER)) {
                @SuppressWarnings("unchecked") final var m = (Map<? extends String, Object>) new ObjectJsonCodec().read(json);
                tag = t.toCharArray();
                options().put(t, m);
            } catch (final IOException | RuntimeException e) {
                throw new IllegalArgumentException("Can't read json: '" + jsonValue + "' (" + x + "," + y + ")", e);
            }
        }

        while (!points.isEmpty() && at(points.get(points.size() - 1)).isSpace()) {
            points.remove(points.size() - 1);
        }

        return new Object(
                points.toArray(Point[]::new), null,
                true, tagDef.length > 0, false, false, null, new String(tag))
                .seal(this);
    }

    private <T> T[] append(final T[] collector, final T value, final IntFunction<T[]> allocator) {
        final var out = allocator.apply(collector.length + 1);
        System.arraycopy(collector, 0, out, 0, collector.length);
        out[collector.length] = value;
        return out;
    }

    private char[] append(final char[] src, final char value) {
        final var out = new char[src.length + 1];
        System.arraycopy(src, 0, out, 0, src.length);
        out[src.length] = value;
        return out;
    }

    private Object[] append(final Object[] src, final Object value) {
        return append(src, value, Object[]::new);
    }

    private Point[] append(final Point[] src, final Point value) {
        return append(src, value, Point[]::new);
    }

    private Object[] findObjects() {
        var objects = objects().value();
        for (int y = 0; y < size[1]; y++) {
            for (int x = 0; x < size[0]; x++) {
                if (isVisited(x, y)) {
                    continue;
                }
                final var ch = at(x, y);
                if (ch.isPathStart()) {
                    visit(x, y);
                    final var objs = scanPath(new Point[]{new Point(x, y, null)});
                    if (objs != null) {
                        for (final var o : objs.value()) {
                            for (final var p : o.points()) {
                                visit(p.x(), p.y());
                            }
                        }
                        for (final var o : objs.value()) {
                            objects = append(objects, o);
                        }
                    }
                }
            }
        }

        for (int y = 0; y < size[1]; y++) {
            for (int x = 0; x < size[0]; x++) {
                if (isVisited(x, y)) {
                    continue;
                }
                final var ch = at(x, y);
                if (ch.isTextStart()) {
                    final var obj = scanText(objects, x, y);
                    if (obj == null) { // unlikely
                        continue;
                    }
                    for (final var p : obj.points()) {
                        visit(p.x(), p.y());
                    }
                    objects = append(objects, obj);
                }
            }
        }

        return Stream.of(objects)
                .sorted()
                .toArray(Object[]::new);
    }

    public Char at(final Point p) {
        return at(p.x(), p.y());
    }

    public Char at(final int x, final int y) {
        return new Char(grid[y * size[0] + x]);
    }
}
