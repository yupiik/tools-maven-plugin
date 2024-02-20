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
package io.yupiik.dev.configuration;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

@DefaultScoped
public class EnableSimpleOptionsArgs { // here the goal is to auto-complete short options (--tool) by prefixing it with the command name.
    public void onStart(@OnEvent @Order(Integer.MIN_VALUE) final Start start, final RuntimeContainer container) {
        ofNullable(container.getBeans().getBeans().get(Args.class))
                .ifPresent(beans -> {
                    final var enriched = enrich(((Args) beans.get(0).create(container, null)).args());
                    container.getBeans().getBeans()
                            .put(Args.class, List.of(new ProvidedInstanceBean<>(DefaultScoped.class, Args.class, () -> enriched)));
                });
    }

    private Args enrich(final List<String> args) {
        if (args == null || args.isEmpty() || args.get(0).startsWith("-") /* not a command */) {
            return new Args(args);
        }
        final var prefix = "--" + args.get(0) + '-';
        final var counter = new AtomicInteger();
        return new Args(args.stream()
                .peek(it -> counter.getAndIncrement())
                .flatMap(i -> !"--".equals(i) && i.startsWith("--") && !i.startsWith(prefix) ?
                        (i.substring("--".length()).contains("-") ?
                                Stream.of(prefix + i.substring("--".length()), args.size() > counter.get() ? args.get(counter.get()) : "", i) :
                                Stream.of(prefix + i.substring("--".length()))) :
                        Stream.of(i))
                .toList());
    }
}
