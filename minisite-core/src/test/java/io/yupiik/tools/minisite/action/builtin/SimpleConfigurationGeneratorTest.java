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

import org.jruby.org.objectweb.asm.ClassWriter;
import org.jruby.org.objectweb.asm.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.jruby.org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.jruby.org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.jruby.org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.jruby.org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.jruby.org.objectweb.asm.Opcodes.ALOAD;
import static org.jruby.org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.jruby.org.objectweb.asm.Opcodes.RETURN;
import static org.jruby.org.objectweb.asm.Opcodes.V11;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleConfigurationGeneratorTest {
    @Test
    @EnabledForJreRange(min = JRE.JAVA_17)
        // simple-configuration is java17 only
    void adoc(@TempDir final Path work) throws IOException {
        final var output = work.resolve("content.adoc");
        final var confClass = "io.yupiik.tools.minisite.action.builtin.SimpleConfigurationGeneratorTest$Conf";
        final var loader = new ClassLoader(Thread.currentThread().getContextClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (confClass.equals(name)) {
                    synchronized (this) {
                        Class<?> loadedClass = super.findLoadedClass(name);
                        if (loadedClass == null) {
                            final var bytes = confClass();
                            loadedClass = super.defineClass(name, bytes, 0, bytes.length);
                        }
                        if (resolve) {
                            resolveClass(loadedClass);
                        }
                        return loadedClass;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        final var thread = Thread.currentThread();
        try {
            thread.setContextClassLoader(loader);

            new SimpleConfigurationGenerator(Map.of("class", confClass, "output", output.toString())).run();
            assertTrue(Files.exists(output));
            assertEquals("" +
                    "[options=\"header\",cols=\"a,a,2\"]\n" +
                    "|===\n" +
                    "|Name|Env Variable|Description\n" +
                    "| `desc` | `DESC` | Something.\n" +
                    "\n" +
                    "|===\n" +
                    "", Files.readString(output));
        } finally {
            thread.setContextClassLoader(loader.getParent());
        }
    }

    /*
    public static class Conf {
        @Param(description = "Something.")
        private String desc;
    }
     */
    public static byte[] confClass() { // uses jruby asm since it is "there" but any asm would work
        final var confClass = new ClassWriter(0);
        confClass.visit(V11, ACC_PUBLIC | ACC_SUPER,
                "io/yupiik/tools/minisite/action/builtin/SimpleConfigurationGeneratorTest$Conf",
                null, "java/lang/Object", null);
        confClass.visitSource("SimpleConfigurationGeneratorTest.java", null);
        confClass.visitNestHost("io/yupiik/tools/minisite/action/builtin/SimpleConfigurationGeneratorTest");
        confClass.visitInnerClass(
                "io/yupiik/tools/minisite/action/builtin/SimpleConfigurationGeneratorTest$Conf",
                "io/yupiik/tools/minisite/action/builtin/SimpleConfigurationGeneratorTest",
                "Conf", ACC_PUBLIC | ACC_STATIC);

        { // the field with the doc
            final var fieldVisitor = confClass.visitField(ACC_PRIVATE, "desc", "Ljava/lang/String;", null, null);
            {
                final var param = fieldVisitor.visitAnnotation("Lio/yupiik/batch/runtime/configuration/Param;", true);
                param.visit("description", "Something.");
                param.visitEnd();
            }
            fieldVisitor.visitEnd();
        }

        { // implicit constructor
            final var start = new Label();
            final var end = new Label();
            final var constructor = confClass.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitLabel(start);
            constructor.visitVarInsn(ALOAD, 0);
            constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitInsn(RETURN);
            constructor.visitLocalVariable(
                    "this", "Lio/yupiik/tools/minisite/action/builtin/SimpleConfigurationGeneratorTest$Conf;",
                    null, start, end, 0);
            constructor.visitMaxs(1, 1);
            constructor.visitEnd();
        }

        confClass.visitEnd();

        return confClass.toByteArray();
    }
}
