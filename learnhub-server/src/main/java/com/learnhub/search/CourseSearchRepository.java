package com.learnhub.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface CourseSearchRepository extends ElasticsearchRepository<CourseSearchDocument, Long> {
}
