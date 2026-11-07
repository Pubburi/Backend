package com.pubburi.pub.controller.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The client may confirm its observed actor, but the live session always chooses the owner. */
public final class FeedbackOwner {
    public static final String HEADER = "X-Pubburi-Feedback-Owner";
    public static final String DESCRIPTION = "Percent-encoded expected current account ID. A mismatch is rejected.";

    private FeedbackOwner() { }

    public static boolean confirmed(HttpServletRequest request, SessionUser owner) {
        return confirmed(request,owner,HEADER);
    }

    public static boolean confirmed(HttpServletRequest request, SessionUser owner,String header) {
        var expected = Collections.list(request.getHeaders(header));
        if (expected.isEmpty()) return false;
        if (expected.size() != 1 || !expected.get(0).matches("(?:[a-zA-Z0-9.!~*'()_-]|%[0-9A-F]{2})++")) {
            throw new IllegalArgumentException("Exactly one canonically encoded expected account header is required");
        }
        // Match encodeURIComponent exactly. URLDecoder silently replaces malformed UTF-8.
        String encoded = URLEncoder.encode(owner.id(), StandardCharsets.UTF_8)
            .replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%7E", "~");
        if (!encoded.equals(expected.get(0))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account session is no longer current");
        }
        return true;
    }
}
