package com.sitionix.forgeai.api.remoteaccess;
import com.sitionix.forgeai.domain.remoteaccess.RemoteAccessClientException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes={RemoteAccessProxyController.class,RemoteAccessOperatorController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RemoteAccessProxyErrors {
    @ExceptionHandler(RemoteAccessClientException.class) public ResponseEntity<RemoteAccessProxyDtos.Error> upstream(RemoteAccessClientException e) {
        return ResponseEntity.status(e.status()).header("Cache-Control","no-store").body(new RemoteAccessProxyDtos.Error(e.code(),e.getMessage(),e.correlationId()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<RemoteAccessProxyDtos.Error> invalid(Exception e) { return error(400,"REMOTE_ACCESS_INVALID_REQUEST","Remote Access request is invalid"); }
    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    public ResponseEntity<RemoteAccessProxyDtos.Error> unauthorized(Exception e) { return error(401,"REMOTE_ACCESS_UNAUTHORIZED","Operator authentication failed"); }
    @ExceptionHandler(Exception.class) public ResponseEntity<RemoteAccessProxyDtos.Error> unavailable(Exception e) { return error(503,"REMOTE_ACCESS_UNAVAILABLE","Remote Access unavailable"); }
    private ResponseEntity<RemoteAccessProxyDtos.Error> error(int status,String code,String message) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new RemoteAccessProxyDtos.Error(code,message,UUID.randomUUID().toString()));
    }
}
