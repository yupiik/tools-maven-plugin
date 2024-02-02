package io.yupiik.dev.test;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Repeatable(Mock.List.class)
@Target(METHOD)
@Retention(RUNTIME)
@ExtendWith(HttpMockExtension.class)
public @interface Mock {
    String method() default "GET";

    String uri();

    String payload();

    @Target(METHOD)
    @Retention(RUNTIME)
    @interface List {
        Mock[] value();
    }
}
