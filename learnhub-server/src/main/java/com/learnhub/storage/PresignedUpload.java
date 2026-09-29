package com.learnhub.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

public record PresignedUpload(URI url, Instant expiresAt, Map<String, String> headers) {}
