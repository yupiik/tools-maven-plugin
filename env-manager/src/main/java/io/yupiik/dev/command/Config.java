/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.dev.provider.central.ApacheMavenConfiguration;
import io.yupiik.dev.provider.central.SingletonCentralConfiguration;
import io.yupiik.dev.provider.github.MinikubeConfiguration;
import io.yupiik.dev.provider.github.SingletonGithubConfiguration;
import io.yupiik.dev.provider.sdkman.SdkManConfiguration;
import io.yupiik.dev.provider.zulu.ZuluCdnConfiguration;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.util.Map;
import java.util.logging.Logger;

import static java.util.stream.Collectors.joining;

@Command(name = "config", description = "Show configuration.")
public class Config implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final SingletonCentralConfiguration central;
    private final SdkManConfiguration sdkman;
    private final SingletonGithubConfiguration github;
    private final ZuluCdnConfiguration zulu;
    private final MinikubeConfiguration minikube;
    private final ApacheMavenConfiguration maven;

    public Config(final Conf conf,
                  final SingletonCentralConfiguration central,
                  final SdkManConfiguration sdkman,
                  final SingletonGithubConfiguration github,
                  final ZuluCdnConfiguration zulu,
                  final MinikubeConfiguration minikube,
                  final ApacheMavenConfiguration maven) {
        this.central = central;
        this.sdkman = sdkman;
        this.github = github;
        this.zulu = zulu;
        this.minikube = minikube;
        this.maven = maven;
    }

    @Override
    public void run() {
        logger.info(() -> Map.of(
                        "central", central.configuration(),
                        "sdkman", sdkman,
                        "github", github.configuration(),
                        "zulu", zulu,
                        "minikube", minikube,
                        "maven", maven)
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    final var value = e.getValue().toString();
                    return "- " + e.getKey() + ": " + value.substring(value.indexOf('[') + 1, value.lastIndexOf(']'));
                })
                .collect(joining("\n")));
    }


    @RootConfiguration("config")
    public record Conf(/* no option yet */) {
    }
}
