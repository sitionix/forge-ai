package com.sitionix.forgeai.api.llm;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.model.llm.LlmAuthorizationFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=ForgeAiLlmAuthorizationController.class)
public class LlmAuthorizationExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<InfrastructureErrorResponse> failure(Exception failure){
        String code;int status;
        if(failure instanceof LlmAuthorizationFailure safe){code=safe.code();status=switch(code){
            case "LOGIN_NOT_FOUND" -> 404;case "INVALID_BROWSER_BINDING" -> 400;
            case "LOGIN_IN_PROGRESS","CODEX_LOGOUT_IN_PROGRESS","CODEX_AUTH_REQUIRED" -> 409;default -> 503;};}
        else if(failure instanceof LlmAuthorizationBrowserGuard.Denied){code="LLM_BROWSER_DENIED";status=403;}
        else if(failure instanceof IllegalArgumentException || failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || failure instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException){code="INVALID_REQUEST";status=400;}
        else {code="CODEX_AUTH_UNAVAILABLE";status=503;}
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new InfrastructureErrorResponse(code,"LLM authorization request failed.",null));
    }
}
