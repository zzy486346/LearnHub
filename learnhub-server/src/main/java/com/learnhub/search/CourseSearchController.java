package com.learnhub.search;

import com.learnhub.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/search/courses")
public class CourseSearchController {
    private final CourseSearchService service;

    public CourseSearchController(CourseSearchService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<SearchResult>> search(@RequestParam(defaultValue = "") String keyword,
                                     @RequestParam(required = false) Set<String> tags,
                                     @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(service.search(keyword, tags, limit));
    }

    @GetMapping("/suggest")
    public ApiResponse<List<String>> suggest(@RequestParam String prefix,
                                @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(service.suggest(prefix, limit));
    }

    @PutMapping("/{courseId}")
    public ApiResponse<CourseSearchDocument> index(@PathVariable Long courseId,
                                      @RequestBody IndexCourse request) {
        return ApiResponse.success(service.index(new CourseSearchDocument(courseId, request.title(), request.description(),
                request.tags(), request.likeCount())));
    }

    public record IndexCourse(String title, String description, Set<String> tags, long likeCount) {}
}
