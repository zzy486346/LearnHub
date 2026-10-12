package com.learnhub.storage;

import java.net.URI;
import java.time.Instant;

public record StoredObjectAccess(URI url, Instant expiresAt) {}
