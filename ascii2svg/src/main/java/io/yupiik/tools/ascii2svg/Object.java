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

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.yupiik.tools.ascii2svg.Object.Dir.H;
import static io.yupiik.tools.ascii2svg.Object.Dir.NE;
import static io.yupiik.tools.ascii2svg.Object.Dir.NONE;
import static io.yupiik.tools.ascii2svg.Object.Dir.NW;
import static io.yupiik.tools.ascii2svg.Object.Dir.SE;
import static io.yupiik.tools.ascii2svg.Object.Dir.SW;
import static io.yupiik.tools.ascii2svg.Object.Dir.V;
import static io.yupiik.tools.ascii2svg.Point.Hint.DOT;
import static io.yupiik.tools.ascii2svg.Point.Hint.END_MARKER;
import static io.yupiik.tools.ascii2svg.Point.Hint.ROUNDED_CORNER;
import static io.yupiik.tools.ascii2svg.Point.Hint.START_MARKER;
import static io.yupiik.tools.ascii2svg.Point.Hint.TICK;

@Data
@Accessors(fluent = true)
public class Object implements Comparable<Object> {
    private final Point[] points;
    private final Point[] corners;
    private final boolean isText;
    private final boolean isTagDefinition;
    private final boolean isClosed;
    private final boolean isDashed;
    private final char[] text;
    private final String tag;

    public boolean hasPoint(final Point p) {
        boolean hasPoint = false;
        final int ncorners = corners.length;
        int j = ncorners - 1;
        for (int i = 0; i < ncorners; i++) {
            if ((corners[i].y() < p.y() && corners[j].y() >= p.y() || corners[j].y() < p.y() && corners[i].y() >= p.y()) &&
                    (corners[i].x() <= p.x() || corners[j].x() <= p.x())) {
                if (corners[i].x() + (p.y() - corners[i].y()) / (corners[j].y() - corners[i].y()) * (corners[j].x() - corners[i].x()) < p.x()) {
                    hasPoint = !hasPoint;
                }
            }
            j = i;
        }
        return hasPoint;
    }

    public Object seal(final Canvas c) {
        final var points = Stream.of(points()).toArray(Point[]::new);
        final var text = new char[points().length];
        if (c.at(points[0]).isArrow()) {
            points[0] = new Point(points[0].x(), points[0].y(), START_MARKER);
        }

        if (c.at(points[points.length - 1]).isArrow()) {
            points[points.length - 1] = new Point(points[points.length - 1].x(), points[points.length - 1].y(), END_MARKER);
        }

        final var cornersAndClosed = pointsToCorners(points);

        int i = 0;
        boolean isDashed = false;
        for (final var p : points) {
            if (!isText()) {
                if (c.at(p).isTick()) {
                    points[i] = new Point(p.x(), p.y(), TICK);
                } else if (c.at(p).isDot()) {
                    points[i] = new Point(p.x(), p.y(), DOT);
                }

                if (c.at(p).isDashed()) {
                    isDashed = true;
                }

                for (final var corner : cornersAndClosed.points()) {
                    if (corner.x() == p.x() && corner.y() == p.y() && c.at(p).isRoundedCorner()) {
                        points[i] = new Point(p.x(), p.y(), ROUNDED_CORNER);
                    }
                }
            }
            text[i] = c.at(p).value();
            i++;
        }

        return new Object(points, cornersAndClosed.points(), isText, isTagDefinition, cornersAndClosed.closed(), isDashed, text, tag);
    }

    public PointState pointsToCorners(final Point[] points) {
        if (points.length < 3) {
            return new PointState(points, false);
        }

        final var out = new AtomicReference<>(new Point[]{points[0]});
        final var dir = new AtomicReference<>(NONE);
        if (points[0].isHorizontal(points[1])) {
            dir.set(H);
        } else if (points[0].isVertical(points[1])) {
            dir.set(V);
        } else if (points[0].isDiagonalSE(points[1])) {
            dir.set(SE);
        } else if (points[0].isDiagonalSW(points[1])) {
            dir.set(SW);
        } else if (points[0].isDiagonalNW(points[1])) {
            dir.set(NW);
        } else if (points[0].isDiagonalNE(points[1])) {
            dir.set(NE);
        } else {
            throw new IllegalArgumentException("discontiguous points: " + java.util.List.of(points));
        }

        for (int i = 2; i < points.length; i++) {
            final BiConsumer<Integer, Dir> cornerFunc = (idx, newDir) -> {
                if (dir.get() != newDir) {
                    out.set(Stream.concat(Stream.of(out.get()), Stream.of(points[idx - 1])).toArray(Point[]::new));
                    dir.set(newDir);
                }
            };
            if (points[i - 1].isHorizontal(points[i])) {
                cornerFunc.accept(i, H);
            } else if (points[i - 1].isVertical(points[i])) {
                cornerFunc.accept(i, V);
            } else if (points[i - 1].isDiagonalSE(points[i])) {
                cornerFunc.accept(i, SE);
            } else if (points[i - 1].isDiagonalSW(points[i])) {
                cornerFunc.accept(i, SW);
            } else if (points[i - 1].isDiagonalNW(points[i])) {
                cornerFunc.accept(i, NW);
            } else if (points[i - 1].isDiagonalNE(points[i])) {
                cornerFunc.accept(i, NE);
            } else {
                throw new IllegalArgumentException("discontiguous points: " + java.util.List.of(points));
            }
        }

        final var last = points[points.length - 1];
        final var closed = new AtomicBoolean(true);
        final Consumer<Dir> closedFunc = newDir -> {
            if (dir.get() != newDir) {
                closed.set(false);
                out.set(Stream.concat(Stream.of(out.get()), Stream.of(last)).toArray(Point[]::new));
            }
        };
        if (points[0].isHorizontal(last)) {
            closedFunc.accept(H);
        } else if (points[0].isVertical(last)) {
            closedFunc.accept(V);
        } else if (last.isDiagonalNE(points[0])) {
            closedFunc.accept(NE);
        } else {
            closed.set(false);
            out.set(Stream.concat(Stream.of(out.get()), Stream.of(last)).toArray(Point[]::new));
        }

        return new PointState(out.get(), closed.get());
    }

    @Override
    public int compareTo(final Object o) {
        if (isText() != o.isText()) {
            return isText() ? 1 : -1;
        }
        final var topDiff = points()[0].y() - o.points()[0].y();
        if (topDiff != 0) {
            return topDiff;
        }
        return points()[0].x() - o.points()[0].x();
    }

    public boolean isTagDefinition() {
        return isTagDefinition;
    }

    @Data
    @Accessors(fluent = true)
    public static class List {
        private final Object[] value;
    }

    public enum Dir {
        NONE, H, V, SE, SW, NW, NE
    }

    @Data
    @Accessors(fluent = true)
    public static class PointState {
        private final Point[] points;
        private final boolean closed;
    }
}
