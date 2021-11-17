/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.tools.minisite.action.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenMetricsToAsciidocTest {
    @Test
    void render(@TempDir final Path tmp) throws IOException {
        final var src = tmp.resolve("from.metrics.txt");
        final var to = tmp.resolve("to.metrics.txt");

        Files.writeString(src, "" +
                "# HELP go_gc_duration_seconds A summary of the GC invocation durations.\n" +
                "# TYPE go_gc_duration_seconds summary\n" +
                "go_gc_duration_seconds{quantile=\"0\"} 0.000140853\n" +
                "go_gc_duration_seconds{quantile=\"0.25\"} 0.000193671\n" +
                "go_gc_duration_seconds{quantile=\"0.5\"} 0.000228633\n" +
                "go_gc_duration_seconds{quantile=\"0.75\"} 0.000268351\n" +
                "go_gc_duration_seconds{quantile=\"1\"} 0.0072365\n" +
                "go_gc_duration_seconds_sum 4.296268945\n" +
                "go_gc_duration_seconds_count 17137\n" +
                "# HELP go_goroutines Number of goroutines that currently exist.\n" +
                "# TYPE go_goroutines gauge\n" +
                "go_goroutines 9\n" +
                "# HELP go_memstats_alloc_bytes Number of bytes allocated and still in use.\n" +
                "# TYPE go_memstats_alloc_bytes gauge\n" +
                "go_memstats_alloc_bytes 2.7110096e+07\n" +
                "# HELP go_memstats_alloc_bytes_total Total number of bytes allocated, even if freed.\n" +
                "# TYPE go_memstats_alloc_bytes_total counter\n" +
                "go_memstats_alloc_bytes_total 1.67440083728e+11\n" +
                "# HELP kafka_topic_partition_oldest_offset Oldest Offset of a Broker at Topic/Partition\n" +
                "# TYPE kafka_topic_partition_oldest_offset gauge\n" +
                "kafka_topic_partition_oldest_offset{partition=\"9\",topic=\"other\"} 2\n" +
                "kafka_topic_partition_oldest_offset{partition=\"9\",topic=\"something\"} 1\n" +
                "# HELP kafka_topic_partition_replicas Number of Replicas for this Topic/Partition\n" +
                "# TYPE kafka_topic_partition_replicas gauge\n" +
                "kafka_topic_partition_replicas{partition=\"0\",topic=\"foo\"} 3\n" +
                "kafka_topic_partition_replicas{partition=\"0\",topic=\"bar\"} 4\n" +
                "kafka_topic_partition_replicas{partition=\"0\",topic=\"dummy\"} 5\n" +
                "", UTF_8);

        new OpenMetricsToAsciidoc(Map.of(
                "source", src.toString(),
                "to", to.toString()))
                .run();
        assertTrue(Files.exists(to));
        assertEquals("" +
                "\n" +
                "==  go_gc_duration_seconds\n" +
                "\n" +
                "A summary of the GC invocation durations.\n" +
                "\n" +
                "[cols=\"2a,6\", options=\"header\"]\n" +
                ".go_gc_duration_seconds\n" +
                "|===\n" +
                "| Tag(s) | Value\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! quantile ! 0\n" +
                "!===\n" +
                "| 1.40853E-4\n" +
                "\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! quantile ! 0.25\n" +
                "!===\n" +
                "| 1.93671E-4\n" +
                "\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! quantile ! 0.5\n" +
                "!===\n" +
                "| 2.28633E-4\n" +
                "\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! quantile ! 0.75\n" +
                "!===\n" +
                "| 2.68351E-4\n" +
                "\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! quantile ! 1\n" +
                "!===\n" +
                "| 0.0072365\n" +
                "\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! _type_ ! sum\n" +
                "!===\n" +
                "| 4.296268945\n" +
                "\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! _type_ ! count\n" +
                "!===\n" +
                "| 17137.0\n" +
                "|===\n" +
                "\n" +
                "==  go_goroutines\n" +
                "\n" +
                "Number of goroutines that currently exist.\n" +
                "\n" +
                "Value: `9.0`.\n" +
                "\n" +
                "\n" +
                "==  go_memstats_alloc_bytes\n" +
                "\n" +
                "Number of bytes allocated and still in use.\n" +
                "\n" +
                "Value: `2.7110096E7`.\n" +
                "\n" +
                "\n" +
                "==  go_memstats_alloc_bytes_total\n" +
                "\n" +
                "Total number of bytes allocated, even if freed.\n" +
                "\n" +
                "Value: `1.67440083728E11`.\n" +
                "\n" +
                "\n" +
                "==  kafka_topic_partition_oldest_offset\n" +
                "\n" +
                "Oldest Offset of a Broker at Topic/Partition\n" +
                "\n" +
                "[cols=\"2a,6\", options=\"header\"]\n" +
                ".kafka_topic_partition_oldest_offset\n" +
                "|===\n" +
                "| Tag(s) | Value\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! partition ! 9\n" +
                "! topic ! other\n" +
                "!===\n" +
                "| 2.0\n" +
                "\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! partition ! 9\n" +
                "! topic ! something\n" +
                "!===\n" +
                "| 1.0\n" +
                "|===\n" +
                "\n" +
                "==  kafka_topic_partition_replicas\n" +
                "\n" +
                "Number of Replicas for this Topic/Partition\n" +
                "\n" +
                "[cols=\"2a,6\", options=\"header\"]\n" +
                ".kafka_topic_partition_replicas\n" +
                "|===\n" +
                "| Tag(s) | Value\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! partition ! 0\n" +
                "! topic ! foo\n" +
                "!===\n" +
                "| 3.0\n" +
                "\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! partition ! 0\n" +
                "! topic ! bar\n" +
                "!===\n" +
                "| 4.0\n" +
                "\n" +
                "| \n" +
                "[stripes=none,cols=\"a,m\"]\n" +
                "!===\n" +
                "! partition ! 0\n" +
                "! topic ! dummy\n" +
                "!===\n" +
                "| 5.0\n" +
                "|===\n" +
                "", Files.readString(to, UTF_8));
    }
}
