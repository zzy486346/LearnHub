package com.learnhub.assistant.chat.model.transport;

public final class ResponseTooLargeException extends RuntimeException {
    public ResponseTooLargeException(int maximumBytes) {
        super("HTTP response exceeds " + maximumBytes + " bytes");
    }
}

