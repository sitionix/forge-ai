package com.sitionix.forgeagent.application.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.path.NodePath;
import com.networknt.schema.path.PathType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import com.sitionix.forgeagent.domain.model.McpToolSummary;
import java.util.*;
import org.springframework.stereotype.Component;

/** Validates saved authoring data; never invokes tools or evaluates expressions. */
@Component
public class TriggerConfigurationValidator {
    private final ObjectMapper json;
    private final SchemaRegistry schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaCacheEnabled(false).resourceLoaders(loaders -> loaders.values(List::clear)
                    .add(iri -> { throw new IllegalArgumentException("External schemas are not allowed."); })));

    public TriggerConfigurationValidator(ObjectMapper json) { this.json = json; }

    public void validate(JsonNode definition, List<McpToolSummary> inventory, Set<McpAllowedTool> approved) {
        try {
            require(definition != null && definition.isObject() && definition.path("version").isIntegralNumber()
                    && definition.path("version").intValue() == 1, "Unsupported configuration version.");
            JsonNode steps = definition.path("steps");
            require(steps.isArray() && !steps.isEmpty(), "Add at least one step.");
            Set<String> previous = new HashSet<>();
            for (JsonNode step : steps) {
                String id = text(step, "id"); UUID.fromString(id);
                require(!previous.contains(id), "Step IDs must be unique.");
                String name = text(step, "toolName"), fingerprint = text(step, "schemaFingerprint");
                var tool = inventory.stream().filter(t -> t.name().equals(name)
                        && t.schemaFingerprint().equals(fingerprint)).findFirst()
                        .orElseThrow(() -> invalid("Tool schema changed or tool is no longer available: " + name));
                require(approved.contains(new McpAllowedTool(name, fingerprint)), "Tool is not approved: " + name);
                require(tool.inputSchema() != null, "Tool schema is missing. Refresh the connection inventory.");
                JsonNode root = json.valueToTree(tool.inputSchema());
                checkReferences(root);
                require(step.path("arguments").isObject() && step.path("bindings").isArray(), "Invalid step parameters.");
                Set<String> targets = new HashSet<>();
                for (JsonNode binding : step.path("bindings")) {
                    String target = pointer(binding, "targetPointer");
                    pointer(binding, "sourcePointer");
                    require(previous.contains(text(binding, "sourceStepId")), "References must point to an earlier step.");
                    require(targets.stream().noneMatch(p -> p.equals(target) || p.startsWith(target + "/")
                            || target.startsWith(p + "/") || p.isEmpty() || target.isEmpty()), "Parameter references overlap.");
                    JsonNode targetSchema=schemaAt(root, target);
                    require(!targetSchema.isBoolean() || targetSchema.booleanValue(), "Parameter is forbidden by schema.");
                    require(step.path("arguments").at(target).isMissingNode()
                            || (target.isEmpty() && step.path("arguments").isEmpty()), "Choose a value or a reference, not both.");
                    targets.add(target);
                }
                validatePart(root, root, step.path("arguments"), "", targets, 0);
                previous.add(id);
            }
            JsonNode result = definition.path("result");
            require(previous.contains(text(result, "stepId")), "Choose an existing result step.");
            pointer(result, "pointer");
        } catch (ValidationException error) { throw error; }
        catch (RuntimeException error) { throw invalid("Invalid trigger configuration: " + error.getMessage()); }
    }

    private void validatePart(JsonNode root, JsonNode rawSchema, JsonNode value, String path, Set<String> bound, int depth) {
        require(depth < 64, "Parameter nesting is too deep.");
        if (bound.contains(path)) return;
        boolean hasBoundChild = bound.stream().anyMatch(p -> p.startsWith(path + "/"));
        if (!hasBoundChild) {
            NodePath location = locate(root, rawSchema, new NodePath(PathType.JSON_POINTER));
            // Synthetic intersections contain original schema nodes. Compile each at its
            // original location so local references retain their document root.
            if(location == null && rawSchema.has("allOf")) {
                for(JsonNode member:rawSchema.path("allOf")) validatePart(root,member,value,path,Set.of(),depth+1);
                return;
            }
            var compiled = location == null ? schemas.getSchema(rawSchema)
                    : location.getNameCount() == 0 ? schemas.getSchema(root) : schemas.getSchema(root).getSubSchema(location);
            require(!value.isMissingNode() && compiled.validate(value).isEmpty(),
                    "Parameters do not match the tool schema at " + (path.isEmpty() ? "/" : path));
            return;
        }
        JsonNode schema = resolve(root, rawSchema);
        // Container-wide constraints involving unknown referenced values are deferred to execution.
        // Every supplied literal subtree is still validated independently.
        if (value.isObject() || (value.isMissingNode() && allowsType(schema,"object") && !"array".equals(schema.path("type").asText()))) {
            require(allowsType(schema,"object"), "Parameter must not be an object at " + path);
            JsonNode required = schema.path("required");
            for (JsonNode key : required) {
                String child = path + "/" + escape(key.asText());
                require(value.has(key.asText()) || bound.stream().anyMatch(p -> p.equals(child) || p.startsWith(child + "/")),
                        "Missing required parameter: " + child);
            }
            Set<String> fields = new LinkedHashSet<>(); value.fieldNames().forEachRemaining(fields::add);
            for (String target : bound) if (target.startsWith(path + "/"))
                fields.add(unescape(target.substring(path.length() + 1).split("/", 2)[0]));
            for (String field : fields) validatePart(root, childSchema(root, schema, field), value.path(field),
                    path + "/" + escape(field), bound, depth + 1);
        } else if (value.isArray() || (value.isMissingNode() && allowsType(schema,"array"))) {
            require(allowsType(schema,"array"), "Parameter must not be an array at " + path);
            for (int i = 0; i < value.size(); i++) validatePart(root, childSchema(root, schema, Integer.toString(i)),
                    value.get(i), path + "/" + i, bound, depth + 1);
        } else throw invalid("Referenced parameter requires an object or array at " + path);
    }

    private NodePath locate(JsonNode current, JsonNode target, NodePath path) {
        if (current == target) return path;
        if (current.isObject()) {
            var fields = current.fields();
            while(fields.hasNext()) {
                var field=fields.next();var found=locate(field.getValue(),target,path.append(field.getKey()));
                if(found!=null)return found;
            }
        } else if(current.isArray()) for(int i=0;i<current.size();i++) {
            var found=locate(current.get(i),target,path.append(i));if(found!=null)return found;
        }
        return null;
    }

    private JsonNode schemaAt(JsonNode root, String pointer) {
        JsonNode current = root;
        if (!pointer.isEmpty()) for (String token : pointer.substring(1).split("/", -1))
            current = childSchema(root, resolve(root, current), unescape(token));
        return current;
    }

    private JsonNode childSchema(JsonNode root, JsonNode schema, String token) {
        schema = resolve(root, schema);
        if (schema.isBoolean()) { require(schema.booleanValue(), "Parameter is forbidden by schema."); return schema; }
        require(!schema.has("oneOf") && !schema.has("anyOf") && !schema.has("allOf"),
                "Reference the whole parameter when its schema contains alternatives.");
        List<JsonNode> matching=new ArrayList<>();
        if(allowsType(schema,"object")) {
            if(schema.path("properties").has(token))matching.add(schema.path("properties").get(token));
            var patterns=schema.path("patternProperties").fields();
            while(patterns.hasNext()) {
                var pattern=patterns.next();
                if(java.util.regex.Pattern.compile(pattern.getKey()).matcher(token).find()) matching.add(pattern.getValue());
            }
        }
        if(!matching.isEmpty()) {
            if(matching.size()==1)return matching.getFirst();
            ObjectNode combined=json.createObjectNode();combined.putArray("allOf").addAll(matching);
            for(String key:List.of("$defs","definitions"))if(root.has(key))combined.set(key,root.get(key));
            return combined;
        }
        if (allowsType(schema,"array") && ("array".equals(schema.path("type").asText()) || schema.has("items") || schema.has("prefixItems"))) {
            require(token.matches("0|[1-9][0-9]*"), "Array reference requires an index.");
            int index = Integer.parseInt(token);
            if (schema.path("prefixItems").has(index)) return schema.path("prefixItems").get(index);
            return schema.has("items") ? schema.get("items") : json.createObjectNode();
        }
        require(allowsType(schema,"object"),"Cannot reference a child of a scalar parameter.");
        JsonNode additional = schema.get("additionalProperties");
        require(additional == null || !additional.isBoolean() || additional.booleanValue(), "Unknown parameter: " + token);
        return additional == null ? json.createObjectNode() : additional;
    }

    private boolean allowsType(JsonNode schema,String type) {
        JsonNode types=schema.get("type");
        if(types==null)return true;
        if(types.isTextual())return type.equals(types.asText());
        for(JsonNode candidate:types)if(type.equals(candidate.asText()))return true;
        return false;
    }

    private JsonNode resolve(JsonNode root, JsonNode schema) {
        Set<String> visited = new HashSet<>();
        while (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            require(ref.startsWith("#") && visited.add(ref), "Unsupported or recursive schema reference.");
            require(schema.size() == 1, "Schema reference siblings require whole-parameter JSON input.");
            schema = root.at(ref.substring(1));
            require(!schema.isMissingNode(), "Unresolved schema reference.");
        }
        return schema;
    }

    private void checkReferences(JsonNode schema) {
        if (schema.isObject()) {
            for (String key : List.of("$ref", "$dynamicRef", "$recursiveRef"))
                if (schema.has(key)) require(schema.path(key).isTextual() && schema.path(key).asText().startsWith("#"), "External schema references are not supported.");
            // Only schema-valued locations, not literal defaults/examples, contain schema references.
            for (String key : List.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas"))
                schema.path(key).elements().forEachRemaining(this::checkReferences);
            for (String key : List.of("items", "additionalProperties", "contains", "not", "if", "then", "else", "propertyNames", "unevaluatedProperties", "unevaluatedItems"))
                if (schema.has(key)) checkReferences(schema.get(key));
            for (String key : List.of("allOf", "anyOf", "oneOf", "prefixItems"))
                schema.path(key).elements().forEachRemaining(this::checkReferences);
        }
    }

    private static String text(JsonNode object, String field) {
        require(object.path(field).isTextual() && !object.path(field).asText().isBlank(), "Missing " + field);
        return object.path(field).asText();
    }
    private static String pointer(JsonNode object, String field) {
        require(object.path(field).isTextual(), "Missing " + field);
        String value = object.path(field).asText();
        require((value.isEmpty() || value.startsWith("/")) && !value.matches(".*~(?![01]).*"), "Invalid JSON Pointer: " + field);
        return value;
    }
    private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private static String unescape(String value) { return value.replace("~1", "/").replace("~0", "~"); }
    private static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private static ValidationException invalid(String message) { return new ValidationException("INVALID_TRIGGER_CONFIGURATION", message); }
}
