package com.learnhub.assistant.chat.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.assistant.qa.GroundedAnswer;
import com.learnhub.assistant.qa.GroundedAnswers;
import com.learnhub.common.exception.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 只展示已经完整解析且通过引用校验的段落；原始 JSON 永不传给浏览器。 */
public final class GroundedAnswerStream implements AutoCloseable {
    private static final Pattern EXTERNAL_LINK = Pattern.compile(
            "(?i)(https?://|www\\.|\\]\\s*\\(|(?:javascript|data|file|ftp):|//[a-z0-9])");
    private final JsonParser parser;
    private final ByteArrayFeeder feeder;
    private final EvidenceBook book;
    private final Runnable checkpoint;
    private final Consumer<String> delta;
    private final String insufficientText;
    private final List<GroundedAnswer.Segment> segments = new ArrayList<>();
    private final Set<String> rootFields = new HashSet<>();
    private final Set<String> segmentFields = new HashSet<>();
    private final StringBuilder rendered = new StringBuilder();
    private State state = State.START;
    private Boolean insufficient;
    private String field;
    private String text;
    private String kind;
    private List<String> evidenceIds;
    private char pendingHigh;
    private int bytes;
    private int bodyLength;
    private boolean closed;
    private boolean finished;

    public GroundedAnswerStream(ObjectMapper mapper, EvidenceBook book, Runnable checkpoint,
            Consumer<String> delta, String insufficientText) {
        this.book = Objects.requireNonNull(book);
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.delta = Objects.requireNonNull(delta);
        this.insufficientText = Objects.requireNonNull(insufficientText);
        try {
            var factory = Objects.requireNonNull(mapper).getFactory().copy();
            factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(16_000).maxNumberLength(20).build());
            parser = factory.createNonBlockingByteArrayParser();
            feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
        } catch (IOException failure) { throw invalid(); }
    }

    public void accept(String rawJsonChunk) {
        requireOpen();
        try {
            if (rawJsonChunk == null) throw invalid();
            checkpoint.run();
            String chunk = pendingHigh == 0 ? rawJsonChunk : pendingHigh + rawJsonChunk;
            pendingHigh = 0;
            if (!chunk.isEmpty() && Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))) {
                pendingHigh = chunk.charAt(chunk.length() - 1);
                chunk = chunk.substring(0, chunk.length() - 1);
            }
            for (int i = 0; i < chunk.length(); i++) {
                char value = chunk.charAt(i);
                if (Character.isHighSurrogate(value)) {
                    if (++i >= chunk.length() || !Character.isLowSurrogate(chunk.charAt(i))) throw invalid();
                } else if (Character.isLowSurrogate(value)) throw invalid();
            }
            byte[] input = chunk.getBytes(StandardCharsets.UTF_8);
            if (input.length > 100_000 - bytes) throw invalid();
            bytes += input.length;
            if (input.length != 0) {
                if (!feeder.needMoreInput()) throw invalid();
                feeder.feedInput(input, 0, input.length);
                drain();
            }
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure instanceof BusinessException business ? business : invalid();
        }
    }

    public String complete(GroundedAnswer verified) {
        requireOpen();
        try {
            if (verified == null || pendingHigh != 0) throw invalid();
            feeder.endOfInput();
            drain();
            if (state != State.DONE || insufficient == null || insufficient != verified.insufficientEvidence()
                    || !segments.equals(verified.answerSegments())) throw invalid();
            String expected = render(verified.answerSegments());
            if (!rendered.toString().equals(expected)) throw invalid();
            checkpoint.run();
            book.check();
            if (insufficient) {
                if (!segments.isEmpty()) throw invalid();
                delta.accept(insufficientText);
                rendered.append(insufficientText);
            }
            finished = true;
            return rendered.toString();
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure instanceof BusinessException business ? business : invalid();
        }
    }

    private void drain() throws IOException {
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
            switch (state) {
                case START -> { require(token == JsonToken.START_OBJECT); state = State.ROOT_FIELDS; }
                case ROOT_FIELDS -> {
                    if (token == JsonToken.END_OBJECT) {
                        require(rootFields.size() == 2 && insufficient != null);
                        require(insufficient ? segments.isEmpty() : !segments.isEmpty());
                        state = State.DONE;
                    } else {
                        require(token == JsonToken.FIELD_NAME);
                        field = parser.currentName();
                        require(Set.of("insufficientEvidence", "answerSegments").contains(field) && rootFields.add(field));
                        state = State.ROOT_VALUE;
                    }
                }
                case ROOT_VALUE -> {
                    if ("insufficientEvidence".equals(field)) {
                        require(token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE);
                        insufficient = token == JsonToken.VALUE_TRUE;
                        require(!insufficient || segments.isEmpty());
                        state = State.ROOT_FIELDS;
                    } else {
                        require(token == JsonToken.START_ARRAY);
                        state = State.SEGMENTS;
                    }
                }
                case SEGMENTS -> {
                    if (token == JsonToken.END_ARRAY) state = State.ROOT_FIELDS;
                    else {
                        require(token == JsonToken.START_OBJECT && segments.size() < 8 && !Boolean.TRUE.equals(insufficient));
                        segmentFields.clear();
                        text = null; kind = null; evidenceIds = null;
                        state = State.SEGMENT_FIELDS;
                    }
                }
                case SEGMENT_FIELDS -> {
                    if (token == JsonToken.END_OBJECT) { emitSegment(); state = State.SEGMENTS; }
                    else {
                        require(token == JsonToken.FIELD_NAME);
                        field = parser.currentName();
                        require(Set.of("text", "evidenceIds", "kind").contains(field) && segmentFields.add(field));
                        state = State.SEGMENT_VALUE;
                    }
                }
                case SEGMENT_VALUE -> {
                    if ("evidenceIds".equals(field)) {
                        require(token == JsonToken.START_ARRAY);
                        evidenceIds = new ArrayList<>(); state = State.IDS;
                    } else {
                        require(token == JsonToken.VALUE_STRING);
                        if ("text".equals(field)) text = parser.getText();
                        else kind = parser.getText();
                        state = State.SEGMENT_FIELDS;
                    }
                }
                case IDS -> {
                    if (token == JsonToken.END_ARRAY) state = State.SEGMENT_FIELDS;
                    else {
                        require(token == JsonToken.VALUE_STRING && evidenceIds.size() < 5);
                        String id = parser.getText();
                        require(!evidenceIds.contains(id) && book.contains(id));
                        evidenceIds.add(id);
                    }
                }
                case DONE -> throw invalid();
            }
        }
    }

    private void emitSegment() {
        require(segmentFields.size() == 3 && text != null && !text.isBlank() && text.length() <= 1_200
                && bodyLength + text.length() <= 4_000 && wellFormedUnicode(text) && !EXTERNAL_LINK.matcher(text).find()
                && !GroundedAnswers.hasInlineCitation(text)
                && evidenceIds != null && !evidenceIds.isEmpty() && Set.of("FACT", "SUGGESTION").contains(kind));
        if ("SUGGESTION".equals(kind)) require(evidenceIds.stream().anyMatch(id -> "PROGRESS".equals(book.kind(id)))
                && evidenceIds.stream().anyMatch(id -> "OUTLINE".equals(book.kind(id))));
        checkpoint.run();
        book.check();
        var segment = new GroundedAnswer.Segment(text, List.copyOf(evidenceIds), kind);
        String addition = (segments.isEmpty() ? "" : "\n\n") + renderSegment(segment);
        delta.accept(addition);
        rendered.append(addition);
        segments.add(segment);
        bodyLength += text.length();
    }

    private static String render(List<GroundedAnswer.Segment> segments) {
        return String.join("\n\n", segments.stream().map(GroundedAnswerStream::renderSegment).toList());
    }

    private static String renderSegment(GroundedAnswer.Segment segment) {
        return segment.text() + " " + String.join("", segment.evidenceIds().stream().map(id -> "[" + id + "]").toList());
    }

    private static boolean wellFormedUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isHighSurrogate(value.charAt(i))) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(value.charAt(i))) return false;
        }
        return true;
    }

    private void requireOpen() { if (closed || finished) throw invalid(); }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static BusinessException invalid() {
        return new BusinessException("ASSISTANT_OUTPUT_INVALID", "模型增量段落未通过校验");
    }
    @Override public void close() {
        if (!closed) {
            closed = true;
            try { parser.close(); } catch (IOException ignored) { }
        }
    }
    private enum State { START, ROOT_FIELDS, ROOT_VALUE, SEGMENTS, SEGMENT_FIELDS, SEGMENT_VALUE, IDS, DONE }
}
