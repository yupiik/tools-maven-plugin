package io.yupiik.dev.test;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.util.AnnotationUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.ofNullable;

public class HttpMockExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(HttpMockExtension.class);

    @Override
    public void beforeEach(final ExtensionContext context) throws Exception {
        final var annotations = AnnotationUtils.findRepeatableAnnotations(context.getTestMethod(), Mock.class);
        if (annotations.isEmpty()) {
            return;
        }
        final var server = HttpServer.create(new InetSocketAddress("localhost", 0), 16);
        server.createContext("/").setHandler(ex -> {
            try (ex) {
                final var method = ex.getRequestMethod();
                final var uri = ex.getRequestURI().toASCIIString();
                final var resp = annotations.stream()
                        .filter(m -> Objects.equals(method, m.method()) && Objects.equals(uri, m.uri()))
                        .findFirst()
                        .orElse(null);
                if (resp == null) {
                    ex.sendResponseHeaders(404, 0);
                } else {
                    final var bytes = resp.payload().getBytes(UTF_8);
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                }
            }
        });
        server.start();
        context.getStore(NAMESPACE).put(HttpServer.class, server);
    }

    @Override
    public void afterEach(final ExtensionContext context) {
        ofNullable(context.getStore(NAMESPACE).get(HttpServer.class, HttpServer.class)).ifPresent(s -> s.stop(0));
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
        return URI.class == parameterContext.getParameter().getType();
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext context) throws ParameterResolutionException {
        return URI.create("http://localhost:" + context.getStore(NAMESPACE).get(HttpServer.class, HttpServer.class).getAddress().getPort() + "/2/");
    }
}
