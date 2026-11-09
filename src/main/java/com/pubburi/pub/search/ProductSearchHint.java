package com.pubburi.pub.search;

import com.pubburi.pub.model.dto.UserPreferences;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public record ProductSearchHint(Kind kind, String query) {
    public static final String HEADER = "X-Pubburi-Search-Hint";
    private static final Pattern HANGUL_TERM = Pattern.compile("[가-힣]{3,20}");

    public enum Kind { ALIAS, TYPO }

    public static ProductSearchHint forQuery(String query) {
        if (query == null) return null;
        String term = query.trim();
        // This alias comes from the reviewed source-to-business category mapping.
        if (term.equals("약주")) return new ProductSearchHint(Kind.ALIAS, "약 · 청주");
        if (!HANGUL_TERM.matcher(term).matches() || UserPreferences.CATEGORIES.contains(term)) return null;

        String candidate = null;
        for (String category : UserPreferences.CATEGORIES) {
            if (!HANGUL_TERM.matcher(category).matches() || !oneSubstitution(term, category)) continue;
            if (candidate != null) return null;
            candidate = category;
        }
        return candidate == null ? null : new ProductSearchHint(Kind.TYPO, candidate);
    }

    private static boolean oneSubstitution(String term, String category) {
        if (term.length() != category.length()) return false;
        int differences = 0;
        // Precomposed Hangul syllables occupy one UTF-16 code unit each.
        for (int index = 0; index < term.length(); index++) {
            if (term.charAt(index) != category.charAt(index) && ++differences > 1) return false;
        }
        return differences == 1;
    }

    public String headerValue() {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
            .replace("+", "%20").replace("%7E", "~").replace("*", "%2A");
        return kind.name() + ":" + encoded;
    }
}
