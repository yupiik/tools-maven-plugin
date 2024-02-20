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
package io.yupiik.maven.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Seed a simple bash CLI.
 */
@Mojo(name = "script-cli-skeleton", requiresProject = false, threadSafe = true)
public class GenerateBashScriptCLISkeletonMojo extends AbstractMojo {
    /**
     * Where to create the bash CLI skeleton.
     */
    @Parameter(property = "yupiik.script-cli-skeleton.directory", required = true)
    protected File directory;

    /**
     * Where to create the bash CLI skeleton.
     */
    @Parameter(property = "yupiik.script-cli-skeleton.generateHelloWorldCommand", defaultValue = "true")
    protected boolean generateHelloWorldCommand;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var commands = directory.toPath().resolve("commands");
        final var mainSh = commands.resolve("../main.sh");
        final var helpSh = commands.resolve("help/index.sh");
        final var helpText = commands.resolve("help/_cli/help.txt");
        try {
            Files.createDirectories(helpText.getParent());
            Files.writeString(mainSh, "" +
                    "#! /usr/bin/env bash\n" +
                    "\n" +
                    "base=\"$(dirname $0)\"\n" +
                    "\n" +
                    "main() {\n" +
                    "  if [ \"$#\" -lt 1 ]; then\n" +
                    "    echo \"[ERROR] No command set, use help to see commands.\"\n" +
                    "    exit 5\n" +
                    "  fi\n" +
                    "\n" +
                    "  sub_command=\"$base/commands/$1/index.sh\"\n" +
                    "  if [ -f \"$sub_command\" ]; then\n" +
                    "    # drop $1 to forward only script args\n" +
                    "    shift\n" +
                    "    MAIN_DIR=\"$base\" SCRIPT_BASE=\"$base/commands/$1\" /usr/bin/env bash \"$sub_command\" \"$@\"\n" +
                    "  else\n" +
                    "    echo \"[ERROR] Unknown command $*, ensure $sub_command exists or fix its name\"\n" +
                    "    exit 6\n" +
                    "  fi\n" +
                    "}\n" +
                    "\n" +
                    "main \"$@\"\n" +
                    "\n");
            Files.writeString(helpSh, "" +
                    "cd \"$SCRIPT_BASE\"\n" +
                    "echo -e \"Commands:\\n\\n\"\n" +
                    "for i in $(ls -d * | sort); do\n" +
                    "  echo -e \"  - $i\"\n" +
                    "  [ -f \"$i/_cli/help.txt\" ] && cat \"$i/_cli/help.txt\" | sed 's/^/    /g'\n" +
                    "  echo\n" +
                    "done\n" +
                    "cd - &> /dev/null\n" +
                    "\n");
            Files.writeString(helpText, "" +
                    "Show this text.\n" +
                    "");
            Files.setPosixFilePermissions(mainSh, PosixFilePermissions.fromString("rwxr-x---"));
            if (generateHelloWorldCommand) {
                final var helloSh = commands.resolve("hello_world/index.sh");
                final var helloText = commands.resolve("hello_world/_cli/help.txt");
                Files.createDirectories(helloText.getParent());
                Files.writeString(helloSh, "" +
                        "echo \"Hello ${1:-<name>}\"\n" +
                        "\n");
                Files.writeString(helloText, "" +
                        "Prints \"Hello <name>\".\n" +
                        "Args: .\n" +
                        "  - name (string)\n" +
                        "");
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
