package io.yupiik.tools.minisite;

public final class Urlifier {
    public String toUrlName(final String string) {
        final StringBuilder out = new StringBuilder()
                .append(Character.toLowerCase(string.charAt(0)));
        for (int i = 1; i < string.length(); i++) {
            final char c = string.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else if (c == ' ') {
                out.append('-');
            } else if (!Character.isJavaIdentifierPart(c)) { // little shortcut for url friendly test
                out.append('-');
            } else {
                out.append(c);
            }
        }
        return out.toString().replace("--", "-");
    }
}
