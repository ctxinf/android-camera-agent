package io.github.ctxinf.cameraagent.domain;

/** Canonical mount path shared by the UI, advertised URLs and request routing. */
public final class HttpPathPrefix {
    private HttpPathPrefix() { }

    public static String normalize(String input) {
        if (input == null || input.trim().length() == 0) return "";
        StringBuilder result = new StringBuilder();
        for (String segment : input.trim().split("/+")) {
            if (segment.length() == 0) continue;
            if (".".equals(segment) || "..".equals(segment)
                    || !segment.matches("[A-Za-z0-9._~-]+")) {
                throw new IllegalArgumentException("路径只能包含字母、数字、斜杠及 - _ . ~，不能包含 . 或 .. 路径段");
            }
            result.append('/').append(segment);
        }
        return result.toString();
    }

    /** Null means outside this mount; match a complete segment, never a partial prefix. */
    public static String strip(String path, String prefix) {
        if (prefix.length() == 0) return path;
        if (path.equals(prefix)) return "/";
        return path.startsWith(prefix + "/") ? path.substring(prefix.length()) : null;
    }
}
