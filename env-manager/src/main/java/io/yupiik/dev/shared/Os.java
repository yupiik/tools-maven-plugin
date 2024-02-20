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
package io.yupiik.dev.shared;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.nio.ByteOrder;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.Locale.ROOT;

@ApplicationScoped
public class Os {
    private final String arch = System.getProperty("os.arch", "");

    public String findOs() {
        final var os = System.getProperty("os.name", "linux").toLowerCase(ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "mac";
        }
        if (os.contains("sunos")) {
            return "solaris";
        }
        if (os.contains("lin") || os.contains("nix") || os.contains("aix")) {
            return "linux";
        }
        return "exotic";
    }

    public boolean is32Bits() {
        return ByteOrder.nativeOrder() == LITTLE_ENDIAN;
    }

    public boolean isArm() {
        return arch.startsWith("arm");
    }

    public boolean isAarch64() {
        return arch.contains("aarch64");
    }

    public boolean isWindows() {
        return "windows".equals(findOs());
    }

    public boolean isUnixLikeTerm() {
        return System.getenv("TERM") != null;
    }
}
