package com.learnhub.assistant.chat.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 从已复核回合生成可追溯的用户原话摘录，不压缩助手事实，不调用模型，不保存覆盖游标。 */
final class ConversationSynopsis {
    static final String VERSION = "user-extract-v1";
    private static final int MAX_CODE_POINTS = 160;

    private ConversationSynopsis() { }

    static Entry extract(long userSeq, long assistantSeq, String user, String assistant,
            String dependencies, String scope) {
        int length = user.codePointCount(0, user.length());
        boolean truncated = length > MAX_CODE_POINTS;
        String excerpt = truncated ? user.substring(0, user.offsetByCodePoints(0, MAX_CODE_POINTS)) : user;
        // 哈希携带原始消息及依赖身份，仅在本轮有效历史中重建，不能拿旧摘录跳过复核。
        return new Entry(userSeq, assistantSeq, "USER_QUOTE", excerpt, truncated,
                hash(scope, VERSION, Long.toString(userSeq), Long.toString(assistantSeq), user, assistant),
                hash(dependencies));
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record Entry(long userSeq, long assistantSeq, String kind, String text, boolean truncated,
                 String messageHash, String dependencyHash) { }
}
