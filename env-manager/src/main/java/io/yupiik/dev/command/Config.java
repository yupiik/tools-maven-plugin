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
package io.yupiik.dev.command;

import io.yupiik.dev.provider.central.CentralConfiguration;
import io.yupiik.dev.provider.central.GavRegistry;
import io.yupiik.dev.provider.github.MinikubeConfiguration;
import io.yupiik.dev.provider.github.SingletonGithubConfiguration;
import io.yupiik.dev.provider.sdkman.SdkManConfiguration;
import io.yupiik.dev.provider.zulu.ZuluCdnConfiguration;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static java.util.stream.Collectors.joining;

@Command(name = "config", description = "Show configuration.")
public class Config implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final CentralConfiguration central;
    private final SdkManConfiguration sdkman;
    private final SingletonGithubConfiguration github;
    private final ZuluCdnConfiguration zulu;
    private final MinikubeConfiguration minikube;
    private final GavRegistry gavs;
    private final Configuration configuration;

    public Config(final Conf conf,
                  final Configuration configuration,
                  final CentralConfiguration central,
                  final SdkManConfiguration sdkman,
                  final SingletonGithubConfiguration github,
                  final ZuluCdnConfiguration zulu,
                  final MinikubeConfiguration minikube,
                  final GavRegistry gavs) {
        this.configuration = configuration;
        this.central = central;
        this.sdkman = sdkman;
        this.github = github;
        this.zulu = zulu;
        this.minikube = minikube;
        this.gavs = gavs;
    }

    @Override
    public void run() {
        logger.info(() -> Stream.concat(
                        Map.of(
                                "central", central.toString(),
                                "sdkman", sdkman.toString(),
                                "github", github.configuration().toString(),
                                "zulu", zulu.toString(),
                                "minikube", minikube.toString()).entrySet().stream(),
                        gavs.gavs().stream()
                                .map(g -> entry(g.artifactId(), "[enabled=" + configuration.get(g.artifactId() + ".enabled").orElse("true") + "]")))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    final var value = e.getValue();
                    return "- " + e.getKey() + ": " + value.substring(value.indexOf('[') + 1, value.lastIndexOf(']'));
                })
                .collect(joining("\n")));
    }


    @RootConfiguration("config")
    public record Conf(/* no option yet */) {
    }
}
