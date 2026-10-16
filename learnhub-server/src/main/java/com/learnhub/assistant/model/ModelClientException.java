package com.learnhub.assistant.model;

import com.learnhub.common.exception.BusinessException;

public final class ModelClientException extends BusinessException {
    public ModelClientException(String code, String message) {
        super(code, message);
    }

    public ModelClientException(String code, String message, Throwable cause) {
        super(code, message);
        initCause(cause);
    }
}
