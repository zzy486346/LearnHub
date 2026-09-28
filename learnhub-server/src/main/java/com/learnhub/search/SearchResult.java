package com.learnhub.search;

import java.util.Set;

public record SearchResult(Long id, String title, String description, String instructor, String coverUrl,
                           Set<String> tags, long likeCount, double score) {
}
