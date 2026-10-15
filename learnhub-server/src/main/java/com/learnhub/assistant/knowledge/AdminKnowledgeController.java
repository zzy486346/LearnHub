package com.learnhub.assistant.knowledge;

import com.learnhub.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {
    private final KnowledgeStore knowledgeStore;

    public AdminKnowledgeController(KnowledgeStore knowledgeStore) {
        this.knowledgeStore = knowledgeStore;
    }

    @PostMapping("/documents")
    public ApiResponse<ImportDocumentResult> importDocument(@Valid @RequestBody ImportDocumentRequest request) {
        return ApiResponse.success(knowledgeStore.importDocument(request));
    }
}
