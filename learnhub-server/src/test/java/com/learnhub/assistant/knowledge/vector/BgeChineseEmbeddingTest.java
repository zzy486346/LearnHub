package com.learnhub.assistant.knowledge.vector;

import static org.assertj.core.api.Assertions.*;
import java.net.SocketPermission;
import java.security.Permission;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BgeChineseEmbeddingTest {
    @Test
    @Order(1)
    @SuppressWarnings("removal")
    void bundledModelRunsWithoutNetworkAndProducesNormalizedChineseVectors() {
        SecurityManager previous = System.getSecurityManager();
        // JDK 17 专项验证：模型、tokenizer、JNI 初始化期间禁止所有 socket 网络操作。
        System.setSecurityManager(new SecurityManager() {
            @Override public void checkPermission(Permission permission) {
                if (permission instanceof SocketPermission) throw new SecurityException("Network disabled in embedding test");
            }
        });
        try (var embedding = new BgeChineseEmbedding()) {
            assertThat(embedding.dimension()).isEqualTo(512);
            var docs = embedding.embedDocuments(List.of("退款后无法继续观看付费课程", "使用面粉和鸡蛋制作蛋糕"));
            var query = embedding.embedQuery("退费以后还能看购买的课吗？");
            assertThat(query.vector()).hasSize(512);
            double norm = 0;
            for (float value : query.vector()) { assertThat(Float.isFinite(value)).isTrue(); norm += value * value; }
            assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(0.001));
            assertThat(dot(query.vector(), docs.get(0).vector()))
                    .isGreaterThan(dot(query.vector(), docs.get(1).vector()));
            assertThat(embedding.embeddingCount()).isEqualTo(3);
        } finally { System.setSecurityManager(previous); }
    }

    @Test
    void countsWithoutTruncationAndSplitsChineseAndEmojiWithoutLosingContent() {
        try (var embedding = new BgeChineseEmbedding()) {
            assertThat(embedding.countTokens("课".repeat(600))).isGreaterThan(512);
            for (String input : List.of("课".repeat(600), "课程😀".repeat(150), "x ".repeat(300))) {
                var parts = embedding.splitDocument(input);
                assertThat(parts.size()).isLessThanOrEqualTo(BgeChineseEmbedding.MAX_PARTS_PER_CHUNK);
                for (String part : parts) {
                    assertThat(embedding.countTokens(part)).isLessThanOrEqualTo(480);
                    assertThat(part.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)).isFalse();
                }
                // 重复文本不能按最长相同后缀推断实际重叠；至少每片都是输入的合法子串。
                assertThat(parts).allMatch(input::contains);
                if (embedding.countTokens(input) > 480) assertThat(parts.size()).isGreaterThan(1);
            }
            String distinct = java.util.stream.IntStream.range(0x4E00, 0x4E00 + 600)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            var parts = embedding.splitDocument(distinct);
            String reconstructed = parts.get(0);
            for (int i = 1; i < parts.size(); i++) {
                int offset = distinct.indexOf(parts.get(i));
                int covered = reconstructed.length();
                assertThat(offset).isLessThanOrEqualTo(covered);
                reconstructed += parts.get(i).substring(covered - offset);
            }
            assertThat(reconstructed).isEqualTo(distinct);
        }
    }

    @Test
    void rejectsInvalidBatchesUnicodeAndOversizeQueriesBeforeEncoding() {
        var embedding = new BgeChineseEmbedding();
        try {
            assertThatThrownBy(() -> embedding.embedDocuments(List.of("课".repeat(600))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> embedding.embedDocuments(java.util.Collections.nCopies(33, "课程")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> embedding.embedQuery("课".repeat(501))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> embedding.splitDocument("\uD800")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> embedding.splitDocument(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThat(embedding.embeddingCount()).isZero();
        } finally { embedding.close(); }
        embedding.close();
        assertThatThrownBy(() -> embedding.embedQuery("课程")).isInstanceOf(IllegalStateException.class);
    }

    private static double dot(float[] left, float[] right) {
        double value = 0;
        for (int i = 0; i < left.length; i++) value += left[i] * right[i];
        return value;
    }
}
