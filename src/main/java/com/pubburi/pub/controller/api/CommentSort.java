package com.pubburi.pub.controller.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public enum CommentSort {
    LATEST, RATING_DESC, RATING_ASC;

    public static CommentSort fromQuery(String[] values) {
        if (values == null) return LATEST;
        if (values.length == 1) {
            return switch (values[0]) {
                case "latest" -> LATEST;
                case "rating_desc" -> RATING_DESC;
                case "rating_asc" -> RATING_ASC;
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid review sort");
            };
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review sort must occur once");
    }
}
