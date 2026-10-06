package com.sitionix.forgeagent.api.llm;

import com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse;
import com.sitionix.forgeagent.domain.exception.LlmAuthorizationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=LlmAuthorizationController.class)
public class LlmAuthorizationExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ForgeAgentErrorResponse> failure(Exception failure){
        String code;int status;
        var authorization=LlmAuthorizationException.find(failure);
        if(failure instanceof LlmAuthorizationController.BrowserDenied){code="LLM_BROWSER_DENIED";status=403;}
        else if(authorization!=null){code=authorization.code();status="CODEX_AUTH_REQUIRED".equals(code)?409:503;}
        else if(failure instanceof IllegalArgumentException && "LOGIN_NOT_FOUND".equals(failure.getMessage())){code="LOGIN_NOT_FOUND";status=404;}
        else if(failure instanceof IllegalStateException && "LOGIN_IN_PROGRESS".equals(failure.getMessage())){code="LOGIN_IN_PROGRESS";status=409;}
        else if(failure instanceof IllegalStateException && "CODEX_LOGOUT_IN_PROGRESS".equals(failure.getMessage())){code="CODEX_LOGOUT_IN_PROGRESS";status=409;}
        else if(failure instanceof IllegalStateException && "CODEX_LOGOUT_REQUIRED".equals(failure.getMessage())){code="CODEX_LOGOUT_REQUIRED";status=503;}
        else if(failure instanceof IllegalArgumentException && "INVALID_BROWSER_BINDING".equals(failure.getMessage())){code="INVALID_BROWSER_BINDING";status=400;}
        else if(failure instanceof IllegalArgumentException || failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || failure instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException){code="INVALID_REQUEST";status=400;}
        else {code="CODEX_AUTH_UNAVAILABLE";status=503;}
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ForgeAgentErrorResponse(code,"LLM authorization request failed.",null));
    }
}
