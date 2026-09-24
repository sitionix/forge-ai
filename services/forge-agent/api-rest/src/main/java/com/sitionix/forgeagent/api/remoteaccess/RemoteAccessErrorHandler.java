package com.sitionix.forgeagent.api.remoteaccess;
import com.sitionix.forgeagent.domain.exception.*;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=RemoteAccessController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RemoteAccessErrorHandler {
    @ExceptionHandler(NotFoundException.class) public ResponseEntity<RemoteAccessDtos.Error> missing(NotFoundException e) { return error(404,e.code(),"Remote Access resource not found"); }
    @ExceptionHandler(ConflictException.class) public ResponseEntity<RemoteAccessDtos.Error> conflict(ConflictException e) { return error(409,e.code(),"Remote Access state conflicts with this operation"); }
    @ExceptionHandler({IllegalArgumentException.class,ValidationException.class,HttpMessageNotReadableException.class,MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<RemoteAccessDtos.Error> invalid(Exception e) { return error(400,"REMOTE_ACCESS_INVALID_REQUEST","Remote Access request is invalid"); }
    @ExceptionHandler(Exception.class) public ResponseEntity<RemoteAccessDtos.Error> unavailable(Exception e) { return error(503,"REMOTE_ACCESS_UNAVAILABLE","Remote Access operation unavailable"); }
    private ResponseEntity<RemoteAccessDtos.Error> error(int status,String code,String message) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new RemoteAccessDtos.Error(code,message,UUID.randomUUID().toString()));
    }
}
