package com.learnhub.search;

import java.util.List;

public record SearchPage(List<SearchResult> records, long total, int current, int size) {}
