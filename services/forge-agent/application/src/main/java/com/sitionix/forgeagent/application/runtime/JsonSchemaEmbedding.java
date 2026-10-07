package com.sitionix.forgeagent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Relocates local references while retaining explicitly identified schema resources. */
public final class JsonSchemaEmbedding {
    private JsonSchemaEmbedding() { }
    public static ObjectNode embed(final JsonNode schema, final String pointer) {
        final ObjectNode copy = schema.deepCopy();
        rewrite(copy,true,pointer);
        return copy;
    }
    private static void rewrite(final JsonNode node, final boolean inheritedRoot, final String pointer) {
        if (node.isObject()) {
            final ObjectNode object = (ObjectNode)node;
            final boolean root = inheritedRoot && !object.has("$id");
            final JsonNode reference = object.get("$ref");
            if (root && reference != null && reference.isTextual()) {
                final String value = reference.textValue();
                if ("#".equals(value)) object.put("$ref",pointer);
                else if (value.startsWith("#/")) object.put("$ref",pointer+value.substring(1));
            }
            object.fields().forEachRemaining(entry -> rewrite(entry.getValue(),root,pointer));
        } else if (node.isArray()) node.forEach(child -> rewrite(child,inheritedRoot,pointer));
    }
}
