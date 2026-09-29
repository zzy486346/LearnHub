package com.learnhub.storage;

public record StoredObjectMetadata(long size, String contentType, String etag) {}
