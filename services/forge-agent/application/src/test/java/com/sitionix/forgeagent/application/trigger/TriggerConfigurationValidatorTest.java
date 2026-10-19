package com.sitionix.forgeagent.application.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TriggerConfigurationValidatorTest {
    final ObjectMapper json = new ObjectMapper();
    final TriggerConfigurationValidator validator = new TriggerConfigurationValidator(json);
    final String first = "11111111-1111-4111-8111-111111111111", second = "22222222-2222-4222-8222-222222222222";
    McpToolSummary tool() throws Exception {
        var schema = json.readValue("""
            {"type":"object","properties":{"query":{"type":"string"},"enabled":{"type":"boolean"},"limit":{"type":"integer","minimum":0}},"required":["query"],"additionalProperties":false}
            """, Map.class);
        return new McpToolSummary("lookup", "Lookup", "sha", schema, null);
    }
    ObjectNode definition() throws Exception {
        return (ObjectNode)json.readTree("""
            {"version":1,"steps":[{"id":"11111111-1111-4111-8111-111111111111","toolName":"lookup","schemaFingerprint":"sha","arguments":{"query":"","enabled":false,"limit":0},"bindings":[]}],"result":{"stepId":"11111111-1111-4111-8111-111111111111","pointer":""}}
            """);
    }
    void validate(ObjectNode definition) throws Exception { validator.validate(definition,List.of(tool()),Set.of(new McpAllowedTool("lookup","sha"))); }
    @Test void acceptsLiteralTypesAndReferencesToEarlierResults() throws Exception {
        var d=definition(); validate(d);
        var next=d.withArray("steps").addObject();next.put("id",second).put("toolName","lookup").put("schemaFingerprint","sha");next.putObject("arguments");
        next.putArray("bindings").addObject().put("targetPointer","/query").put("sourceStepId",first).put("sourcePointer","/items/0/name");
        validate(d);
    }
    @Test void rejectsWrongLiteralTypeAndMissingRequiredParameter() throws Exception {
        var d=definition();((ObjectNode)d.at("/steps/0/arguments")).put("limit","zero");
        assertThatThrownBy(()->validate(d)).isInstanceOf(ValidationException.class);
        ((ObjectNode)d.at("/steps/0/arguments")).remove("limit");((ObjectNode)d.at("/steps/0/arguments")).remove("query");
        assertThatThrownBy(()->validate(d)).isInstanceOf(ValidationException.class);
    }
    @Test void rejectsForwardReferencesDuplicateStepsAndConflictingTargets() throws Exception {
        var d=definition();((ObjectNode)d.at("/steps/0")).withArray("bindings").addObject().put("targetPointer","/query").put("sourceStepId",second).put("sourcePointer","");
        assertThatThrownBy(()->validate(d)).isInstanceOf(ValidationException.class);
        var duplicate=definition();duplicate.withArray("steps").add(duplicate.at("/steps/0").deepCopy());
        assertThatThrownBy(()->validate(duplicate)).isInstanceOf(ValidationException.class);
    }
    @Test void rejectsStaleOrUnapprovedToolsAndUnsupportedVersion() throws Exception {
        var d=definition();
        assertThatThrownBy(()->validator.validate(d,List.of(tool()),Set.of())).isInstanceOf(ValidationException.class);
        ((ObjectNode)d.at("/steps/0")).put("schemaFingerprint","old");assertThatThrownBy(()->validate(d)).isInstanceOf(ValidationException.class);
        var unknown=definition();unknown.put("version",2);assertThatThrownBy(()->validate(unknown)).isInstanceOf(ValidationException.class);
    }
    @Test void rejectsExternalSchemaReferencesWithoutFetching() throws Exception {
        var t=new McpToolSummary("lookup",null,"sha",Map.of("$ref","https://example.test/schema"),null);
        assertThatThrownBy(()->validator.validate(definition(),List.of(t),Set.of(new McpAllowedTool("lookup","sha")))).isInstanceOf(ValidationException.class);
    }
    @Test void validatesLocalReferencesWithSiblingConstraintsAsWholeLiteralInput() throws Exception {
        var t=new McpToolSummary("lookup",null,"sha",Map.of(
            "$defs",Map.of("args",Map.of("type","object","properties",Map.of("query",Map.of("type","string")))),
            "$ref","#/$defs/args", "required",List.of("query")),null);
        validator.validate(definition(),List.of(t),Set.of(new McpAllowedTool("lookup","sha")));
    }
    @Test void rejectsBindingsBelowScalarParameters() throws Exception {
        var d=definition();var next=d.withArray("steps").addObject();
        next.put("id",second).put("toolName","lookup").put("schemaFingerprint","sha");
        next.putObject("arguments").put("query","valid").putObject("limit");
        next.putArray("bindings").addObject().put("targetPointer","/limit/x").put("sourceStepId",first).put("sourcePointer","");
        assertThatThrownBy(()->validate(d)).isInstanceOf(ValidationException.class);
    }
    @Test void validatesPatternPropertiesEvenWhenAnotherParameterIsBound() throws Exception {
        var t=new McpToolSummary("lookup",null,"sha",Map.of("type","object", "properties",Map.of("query",Map.of("type","string")),
                "patternProperties",Map.of("^x",Map.of("type","integer"))),null);
        var d=definition();var next=d.withArray("steps").addObject();
        next.put("id",second).put("toolName","lookup").put("schemaFingerprint","sha");next.putObject("arguments").put("x","BAD");
        next.putArray("bindings").addObject().put("targetPointer","/query").put("sourceStepId",first).put("sourcePointer","");
        assertThatThrownBy(()->validator.validate(d,List.of(t),Set.of(new McpAllowedTool("lookup","sha")))).isInstanceOf(ValidationException.class);
    }
    @Test void preservesOriginalRootForIntersectingPropertyAndPatternReferences() throws Exception {
        var t=new McpToolSummary("lookup",null,"sha",Map.of("type","object",
                "properties",Map.of("query",Map.of("type","string"),"base",Map.of("type","integer"),"x",Map.of("$ref","#/properties/base")),
                "patternProperties",Map.of("^x",Map.of("minimum",0))),null);
        var d=definition();var next=d.withArray("steps").addObject();
        next.put("id",second).put("toolName","lookup").put("schemaFingerprint","sha");next.putObject("arguments").put("x",3);
        next.putArray("bindings").addObject().put("targetPointer","/query").put("sourceStepId",first).put("sourcePointer","");
        validator.validate(d,List.of(t),Set.of(new McpAllowedTool("lookup","sha")));
        ((ObjectNode)next.path("arguments")).put("x",-1);
        assertThatThrownBy(()->validator.validate(d,List.of(t),Set.of(new McpAllowedTool("lookup","sha")))).isInstanceOf(ValidationException.class);
        ((ObjectNode)next.path("arguments")).put("x","wrong");
        assertThatThrownBy(()->validator.validate(d,List.of(t),Set.of(new McpAllowedTool("lookup","sha")))).isInstanceOf(ValidationException.class);
    }
}
