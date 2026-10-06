package com.sitionix.forgeagent.api.llm;

import com.fasterxml.jackson.databind.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.ForgeCodexOperationsPort;
import com.sitionix.forgeagent.domain.exception.LlmAuthorizationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping(ForgeCodexInternalController.PREFIX)
public class ForgeCodexInternalController {
    public static final String PREFIX="/internal/v1/codex";
    private final ForgeCodexOperationsPort operations;
    private final ObjectMapper mapper;
    public ForgeCodexInternalController(ForgeCodexOperationsPort operations,ObjectMapper mapper){this.operations=operations;this.mapper=mapper;}
    @ModelAttribute public void guard(HttpServletRequest request) {
        if(!Boolean.TRUE.equals(request.getAttribute(ForgeCodexServiceAuthFilter.ALLOWED))) throw new SecurityException();
    }
    @GetMapping("/models") public ResponseEntity<?> models(@RequestParam Map<String,String> query) {
        if(!Set.of("cursor","limit","includeHidden").containsAll(query.keySet()) || query.getOrDefault("cursor","").length()>4096
                || query.containsKey("includeHidden") && !Set.of("true","false").contains(query.get("includeHidden"))) throw new IllegalArgumentException();
        Integer limit=query.containsKey("limit")?Integer.valueOf(query.get("limit")):null;
        if(limit!=null && (limit<1 || limit>100))throw new IllegalArgumentException();
        return snapshot(operations.models(query.get("cursor"),limit,query.containsKey("includeHidden")?Boolean.valueOf(query.get("includeHidden")):null));
    }
    @GetMapping("/usage") public ResponseEntity<?> usage(@RequestParam Map<String,String> query) {
        if(!query.isEmpty())throw new IllegalArgumentException();return snapshot(operations.usage());
    }
    private ResponseEntity<?> snapshot(ForgeCodexOperationsPort.Snapshot value){return ResponseEntity.ok().header("X-Forge-Codex-Version",value.serverVersion()).body(value.payload());}
    @PostMapping(value="/generations",consumes="application/json") public ResponseEntity<?> generate(HttpServletRequest request)throws Exception {
        // Six escaped bytes per UTF-8 prompt byte, plus small metadata. Bound before parsing.
        byte[] body=request.getInputStream().readNBytes(6*1024*1024+8193);
        if(body.length>6*1024*1024+8192)throw new IllegalArgumentException();
        JsonNode json=mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        var fields=Set.of("requestId","prompt","modelId","effortId","responseMode","timeoutSeconds");
        if(json==null || !json.isObject() || json.size()!=6)throw new IllegalArgumentException();
        for(var names=json.fieldNames();names.hasNext();)if(!fields.contains(names.next()))throw new IllegalArgumentException();
        for(String key:List.of("requestId","prompt","modelId","responseMode"))if(!json.path(key).isTextual())throw new IllegalArgumentException();
        if(!json.path("timeoutSeconds").isNumber() || !json.path("effortId").isNull() && !json.path("effortId").isTextual())throw new IllegalArgumentException();
        var input=new ForgeCodexGenerationRequest(UUID.fromString(json.get("requestId").textValue()),json.get("prompt").textValue(),json.get("modelId").textValue(),
                json.get("effortId").isNull()?null:json.get("effortId").textValue(),json.get("responseMode").textValue(),json.get("timeoutSeconds").doubleValue());
        return ResponseEntity.accepted().body(Map.of("requestId",operations.submit(input)));
    }
    @GetMapping("/generations/{id}") public ForgeCodexGenerationResult get(@PathVariable UUID id){return operations.get(id);}
    @DeleteMapping("/generations/{id}") public ResponseEntity<?> cancel(@PathVariable UUID id){operations.cancel(id);return ResponseEntity.noContent().build();}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failure(Exception failure) {
        var auth=LlmAuthorizationException.find(failure);
        int status=503;String code="CODEX_BRIDGE_UNAVAILABLE";
        if(auth!=null){code=auth.code();status="CODEX_AUTH_REQUIRED".equals(code)?409:503;}
        else if(failure instanceof SecurityException){status=403;code="CODEX_SERVICE_DENIED";}
        else if(failure instanceof NoSuchElementException){status=404;code="CODEX_JOB_NOT_FOUND";}
        else if(failure instanceof IllegalStateException){status=409;code="CODEX_JOB_CONFLICT";}
        else if(failure instanceof IllegalArgumentException || failure instanceof com.fasterxml.jackson.core.JacksonException){status=400;code="INVALID_REQUEST";}
        return ResponseEntity.status(status).body(Map.of("code",code));
    }
}
