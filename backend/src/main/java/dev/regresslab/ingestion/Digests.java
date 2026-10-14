package dev.regresslab.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

public final class Digests {
    private Digests() {}
    public static String bytes(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    // Canonical-json-v1: recursively sort object keys, preserve arrays/numbers.
    public static String canonical(ObjectMapper mapper, Object value) {
        return bytes(sort(mapper.valueToTree(value)).toString().getBytes(StandardCharsets.UTF_8));
    }
    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(e -> fields.put(e.getKey(), sort(e.getValue())));
            ObjectNode output = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
            fields.forEach(output::set);
            return output;
        }
        if (node.isArray()) {
            ArrayNode output = new ArrayNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
            node.forEach(child -> output.add(sort(child)));
            return output;
        }
        return node;
    }
}
