package com.learnhub.search;

public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException(Throwable cause) {
        super("课程搜索暂时不可用，请稍后重试", cause);
    }
}
