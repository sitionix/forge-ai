package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Presentation only: the existing MCP handler still owns every public error mapping. */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(assignableTypes={ForgeAiMcpOAuthController.class,McpConnectionsExceptionHandler.class})
public class McpOAuthBrowserResponseAdvice implements ResponseBodyAdvice<Object> {
    public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return true;}
    public Object beforeBodyWrite(Object body,MethodParameter method,MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
        if(!(body instanceof InfrastructureErrorResponse) || request.getMethod()!=HttpMethod.GET
                || !(request instanceof ServletServerHttpRequest servlet)
                || !servlet.getServletRequest().getRequestURI().equals(servlet.getServletRequest().getContextPath()+McpOAuthBrowserResult.CALLBACK))return body;
        UUID transaction=null;
        try {transaction=McpOAuthBrowserResult.transaction(servlet.getServletRequest().getParameter("state"));}
        catch(IllegalArgumentException ignored) { /* Malformed state never becomes a redirect parameter. */ }
        response.setStatusCode(HttpStatus.SEE_OTHER);response.getHeaders().setLocation(McpOAuthBrowserResult.location(transaction,null));
        response.getHeaders().setCacheControl("no-store");response.getHeaders().set("Referrer-Policy","no-referrer");
        return null;
    }
}
