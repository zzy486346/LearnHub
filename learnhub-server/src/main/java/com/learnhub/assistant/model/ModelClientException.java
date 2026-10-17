package com.learnhub.assistant.model;

import com.learnhub.common.exception.BusinessException;

public final class ModelClientException extends BusinessException {
    private Usage usage;
    public ModelClientException(String code, String message) {
        super(code, message);
    }

    public ModelClientException(String code, String message, Throwable cause) {
        super(code, message);
        initCause(cause);
    }

    public Usage usage() { return usage; }

    ModelClientException withUsage(Usage usage) {
        this.usage = usage;
        return this;
    }
}
