package com.pubburi.pub.search;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** The public name-suggestion input contract shared by both read paths. */
public final class ProductNamePrefix {
    public static final int LIMIT = 8;
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)^\\s+|\\s+$");
    private ProductNamePrefix() { }

    public static String normalize(String query) {
        if (query.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Product name prefix contains a control character");
        String prefix = Normalizer.normalize(EDGE_WHITESPACE.matcher(query).replaceAll(""), Normalizer.Form.NFC);
        int length = prefix.codePointCount(0, prefix.length());
        if (length > 100) throw new IllegalArgumentException("Product name prefix exceeds 100 code points");
        return length < 2 ? "" : prefix;
    }
}
