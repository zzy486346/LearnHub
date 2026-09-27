package com.learnhub.search;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/search/courses")
public class CourseSearchController {
    private final CourseSearchService service;

    public CourseSearchController(CourseSearchService service) { this.service = service; }

    @GetMapping
    public List<SearchResult> search(@RequestParam(defaultValue = "") String keyword,
                                     @RequestParam(required = false) Set<String> tags,
                                     @RequestParam(defaultValue = "20") int limit) {
        return service.search(keyword, tags, limit);
    }

    @GetMapping("/suggest")
    public List<String> suggest(@RequestParam String prefix,
                                @RequestParam(defaultValue = "10") int limit) {
        return service.suggest(prefix, limit);
    }

    @PutMapping("/{courseId}")
    public CourseSearchDocument index(@PathVariable Long courseId,
                                      @RequestBody IndexCourse request) {
        return service.index(new CourseSearchDocument(courseId, request.title(), request.description(),
                request.tags(), request.likeCount()));
    }

    public record IndexCourse(String title, String description, Set<String> tags, long likeCount) {}
}
