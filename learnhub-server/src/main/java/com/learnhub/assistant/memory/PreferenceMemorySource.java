package com.learnhub.assistant.memory;

import java.util.List;

public interface PreferenceMemorySource {
    List<PreferenceSnapshot> context(long owner, String query, int limit);
    PreferenceSnapshot current(long owner, String preferenceId);
}
