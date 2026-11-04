package com.pubburi.pub.model.dto;

/** A member opinion; never a curated product feature or relevance label. */
public record CommentTasteTag(int commentId, String tag) { }
