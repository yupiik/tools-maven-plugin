package io.yupiik.asciidoc.model;

import java.util.Map;

import static io.yupiik.asciidoc.model.Element.ElementType.PAGE_BREAK;

public record PageBreak(Map<String, String> options) implements Element {
    @Override
    public ElementType type() {
        return PAGE_BREAK;
    }
}
