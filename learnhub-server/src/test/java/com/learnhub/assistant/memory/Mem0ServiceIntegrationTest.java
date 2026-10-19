package com.learnhub.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 仅由自有隔离容器脚本启用；实际 Java HTTP → Mem0 SDK → 本地 BGE → PG。 */
@EnabledIfSystemProperty(named = "learnhub.memory.service.it.enabled", matches = "true")
class Mem0ServiceIntegrationTest {
    @Test void realPrivateServiceSupportsOwnedVersionedPreferenceLifecycle() {
        String id = UUID.randomUUID().toString();
        long owner = Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 1) + 1;
        long other = owner == Long.MAX_VALUE ? owner - 1 : owner + 1;
        try (var client = new Mem0HttpClient(URI.create(System.getenv("LEARNHUB_MEMORY_IT_BASE_URL")),
                System.getenv("LEARNHUB_MEMORY_IT_TOKEN"), Duration.ofSeconds(10))) {
            try {
                client.upsert(owner, id, 1, "PACE", "每晚学习二十分钟");
                client.upsert(owner, id, 1, "PACE", "每晚学习二十分钟");
                assertThat(client.search(owner, "每晚学习多久", 3)).contains(new Mem0Client.Hit(id, 1));
                assertThat(client.search(other, "每晚学习多久", 3)).isEmpty();
                client.upsert(owner, id, 2, "PACE", "每晚学习三十分钟");
                assertThat(client.search(owner, "学习时间", 3)).containsExactly(new Mem0Client.Hit(id, 2));
                client.delete(owner, id, 3);
                client.delete(owner, id, 3);
                assertThat(client.search(owner, "学习时间", 3)).isEmpty();
            } finally {
                // 失败分支仍尝试墓碑，完整基础设施由外层 finally 清理。
                client.delete(owner, id, 3);
            }
        }
    }
}
