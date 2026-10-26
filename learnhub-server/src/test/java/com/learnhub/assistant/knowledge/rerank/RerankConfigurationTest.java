package com.learnhub.assistant.knowledge.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RerankConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(RerankConfiguration.class);

    @Test void disabledNeedsNoKeyAndCreatesNoRemoteClient() {
        runner.withPropertyValues("LEARNHUB_VECTOR_SILICONFLOW_API_KEY=")
                .run(context -> assertThat(context).doesNotHaveBean(KnowledgeReranker.class));
    }

    @Test void enabledRequiresKeyAndRejectsInvalidBoundsBeforeNetwork() {
        runner.withPropertyValues("learnhub.rerank.enabled=true", "learnhub.rerank.api-key=",
                "LEARNHUB_VECTOR_SILICONFLOW_API_KEY=").run(context -> assertThat(context).hasFailed());
        for (String property : List.of("learnhub.rerank.candidates=1", "learnhub.rerank.candidates=51",
                "learnhub.rerank.timeout=11s", "learnhub.rerank.endpoint=https://example.invalid/v1/rerank")) {
            runner.withPropertyValues("learnhub.rerank.enabled=true", "learnhub.rerank.api-key=" + "x".repeat(40), property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void emptyOverrideReusesEmbeddingKeyWithoutVectorProfile() throws Exception {
        verifiesAuthorization("", "shared-test-key-" + "x".repeat(32));
    }

    @Test void explicitOverrideWinsOverEmbeddingKey() throws Exception {
        verifiesAuthorization("override-test-key-" + "y".repeat(32), "shared-test-key-" + "x".repeat(32));
    }

    private void verifiesAuthorization(String override, String shared) throws Exception {
        var authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 2);
        server.createContext("/v1/rerank", exchange -> {
            try {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            runner.withPropertyValues("learnhub.rerank.enabled=true", "learnhub.rerank.api-key=" + override,
                    "LEARNHUB_VECTOR_SILICONFLOW_API_KEY=" + shared,
                    "learnhub.rerank.endpoint=http://127.0.0.1:" + server.getAddress().getPort() + "/v1/rerank")
                    .run(context -> {
                        assertThat(context).hasSingleBean(KnowledgeReranker.class);
                        var reranker = context.getBean(KnowledgeReranker.class);
                        assertThat(reranker.candidateLimit()).isEqualTo(20);
                        assertThat(reranker.rerank("课程", List.of("蛋糕食谱", "Java课程")))
                                .extracting(KnowledgeReranker.Ranked::index).containsExactly(1, 0);
                        assertThat(authorization.get()).isEqualTo("Bearer " + (override.isBlank() ? shared : override));
                    });
        } finally { server.stop(0); }
    }
}
