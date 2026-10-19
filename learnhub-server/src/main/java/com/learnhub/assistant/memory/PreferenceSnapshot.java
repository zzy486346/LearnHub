package com.learnhub.assistant.memory;

/** 仅学习偏好，不可作为课程事实证据或工具指令。 */
public record PreferenceSnapshot(String id, long version, String category, String text) { }
