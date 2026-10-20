package com.learnhub.assistant.qa;

/** 说明结果如何产生；合法引用不代表已经证明回答的语义正确性。 */
public enum AnswerEvidenceOutcome {
    NO_COURSE_ANSWER,
    NO_SOURCES_SERVER,
    MODEL_REPORTED_INSUFFICIENT,
    MODEL_ANSWER_WITH_VALID_REFERENCES;

    public static final String INSUFFICIENT_TEXT = "现有课程资料不足以回答这个问题。";
}
