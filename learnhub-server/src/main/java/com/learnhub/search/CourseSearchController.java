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
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(service.searchPage(keyword, tags, page, limit).records());
    }

    @GetMapping({"/suggest", "/suggestions"})
    public ApiResponse<List<String>> suggest(@RequestParam String prefix,
                                @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(service.suggest(prefix, limit));
    }

    @GetMapping("/page")
    public ApiResponse<SearchPage> page(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Set<String> tags,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.searchPage(keyword, tags, page, size));
    }
}
