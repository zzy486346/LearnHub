package com.learnhub.interaction.like;

final class LikeRedisKeys {
    static final String SLOT = "{likes}";
    static final String PENDING_PUBLISH = "learnhub:like:" + SLOT + ":pending-publish";
    static final String LIVE_DELTA = "learnhub:like:" + SLOT + ":delta";
    static final String LIVE_RELATIONS = "learnhub:like:" + SLOT + ":relations";
    static final String LIVE_EVENTS = "learnhub:like:" + SLOT + ":events";
    static final String SEQUENCE = "learnhub:like:" + SLOT + ":sequence";
    static final String RECONCILE_LOCK = "learnhub:like:" + SLOT + ":reconcile-lock";
    static final String TARGETS = "learnhub:like:" + SLOT + ":targets";
    static final String PROCESSING_BATCHES = "learnhub:like:" + SLOT + ":processing-batches";

    private LikeRedisKeys() {}

    static String userSet(LikeTargetType type, Long targetId) {
        return "learnhub:like:" + SLOT + ":users:" + type.name().toLowerCase() + ":" + targetId;
    }

    static String eventDedup(String eventId) {
        return "learnhub:like:" + SLOT + ":event:" + eventId;
    }

    static String batchDelta(String batchId) {
        return "learnhub:like:" + SLOT + ":batch:" + batchId + ":delta";
    }

    static String batchRelations(String batchId) {
        return "learnhub:like:" + SLOT + ":batch:" + batchId + ":relations";
    }

    static String batchEvents(String batchId) {
        return "learnhub:like:" + SLOT + ":batch:" + batchId + ":events";
    }

    static String targetField(LikeTargetType type, Long targetId) {
        return type.name() + ":" + targetId;
    }

    static String relationField(LikeEvent event) {
        return event.targetType().name() + ":" + event.targetId() + ":" + event.userId();
    }
}
