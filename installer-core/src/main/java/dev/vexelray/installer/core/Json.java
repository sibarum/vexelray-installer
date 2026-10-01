package dev.vexelray.installer.core;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/** JSON in and out. Output is ASCII-only (non-ASCII escaped) and uses LF, so it survives any transport. */
final class Json {
    private Json() {}

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final ObjectMapper READER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final ObjectMapper WRITER = JsonMapper.builder()
            .enable(JsonGenerator.Feature.ESCAPE_NON_ASCII)
            .build();

    static ObjectNode object() { return NODES.objectNode(); }

    static <T> T read(String json, Class<T> type) throws IOException { return READER.readValue(json, type); }

    static JsonNode tree(String json) throws IOException { return READER.readTree(json); }

    static String pretty(JsonNode node) {
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(indent).withArrayIndenter(indent);
        try {
            return WRITER.writer(pp).writeValueAsString(node) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String compact(JsonNode node) {
        try {
            return WRITER.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
