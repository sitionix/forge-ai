package com.sitionix.forgeai.api.llm;

import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice(assignableTypes={ForgeAiLlmAuthorizationController.class,LlmAuthorizationExceptionHandler.class})
public class LlmAuthorizationResponseAdvice implements ResponseBodyAdvice<Object> {
    public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return true;}
    public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){response.getHeaders().setCacheControl("no-store");response.getHeaders().set("Referrer-Policy","no-referrer");return body;}
}
