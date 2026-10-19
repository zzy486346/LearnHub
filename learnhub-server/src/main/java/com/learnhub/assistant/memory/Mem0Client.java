package com.learnhub.assistant.memory;

import java.util.List;

/** 内部服务接口；owner 只由服务器身份传入，远端不接收浏览器过滤器。 */
public interface Mem0Client {
    void upsert(long owner, String preferenceId, long version, String category, String text);
    void delete(long owner, String preferenceId, long version);
    List<Hit> search(long owner, String query, int limit);
    record Hit(String preferenceId, long version) { }
}
