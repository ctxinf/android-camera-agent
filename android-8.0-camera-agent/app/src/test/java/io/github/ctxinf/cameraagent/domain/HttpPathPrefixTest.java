package io.github.ctxinf.cameraagent.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class HttpPathPrefixTest {
    @Test public void normalizesRootSlashesAndNestedPaths() {
        assertEquals("", HttpPathPrefix.normalize(null));
        assertEquals("", HttpPathPrefix.normalize(" / "));
        assertEquals("/camera/front", HttpPathPrefix.normalize(" camera//front/ "));
        assertEquals("/cam-1/v1.0/~local_a", HttpPathPrefix.normalize("cam-1/v1.0/~local_a"));
    }
    @Test public void rejectsUrlsTraversalAndEncodedSeparators() {
        for (String value : new String[] {"https://host/cam", "/cam?x=1", "/cam#x", "/a/../b", "/./a", "/a%2fb", "/a b", "/a\\b"}) {
            try { HttpPathPrefix.normalize(value); fail(value); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void matchesOnlyWholeSegments() {
        assertEquals("/api/status", HttpPathPrefix.strip("/cam/api/status", "/cam"));
        assertEquals("/", HttpPathPrefix.strip("/cam", "/cam"));
        assertNull(HttpPathPrefix.strip("/camera/api/status", "/cam"));
        assertNull(HttpPathPrefix.strip("/api/status", "/cam"));
        assertEquals("/api/status", HttpPathPrefix.strip("/api/status", ""));
    }
}
