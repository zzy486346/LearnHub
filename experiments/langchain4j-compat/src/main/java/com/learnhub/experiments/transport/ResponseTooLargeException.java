package com.learnhub.experiments.transport;

public final class ResponseTooLargeException extends RuntimeException {
    public ResponseTooLargeException(int maximumBytes) {
        super("HTTP response exceeds " + maximumBytes + " bytes");
    }
}
