package com.pubburi.pub.controller.api;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CommentTasteTags {
    private CommentTasteTags() { }
    /** Uses the existing product tag rules, without Unicode compatibility folding. */
    public static List<String> normalize(List<String> values) {
        if (values == null) return null; // Omitted update preserves stored opinions.
        if (values.size() > 20) throw invalid();
        var tags = new TreeSet<String>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0) throw invalid();
            // A valid surrogate pair is one code point; lone UTF-16 units cannot survive JDBC UTF-8.
            if (value.codePoints().anyMatch(point -> point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE)) throw invalid();
            String tag = value.strip().toLowerCase(Locale.ROOT);
            if (tag.length() > 40 || !tags.add(tag)) throw invalid();
        }
        return List.copyOf(tags);
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid review taste tags");
    }
}
