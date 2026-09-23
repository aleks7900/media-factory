package com.mediafactory.api;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataIntegrityViolationException;
@RestControllerAdvice
public class ApiErrors {
 @ExceptionHandler(com.mediafactory.provider.resilience.ImageGenerationException.class)
 public ProblemDetail provider(com.mediafactory.provider.resilience.ImageGenerationException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,e.getMessage()); }
 @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
 public ProblemDetail status(org.springframework.web.server.ResponseStatusException e) { return ProblemDetail.forStatusAndDetail(e.getStatusCode(),e.getReason()==null?"Request failed":e.getReason()); }
 @ExceptionHandler(DataIntegrityViolationException.class)
 public ProblemDetail conflict(DataIntegrityViolationException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"A referenced record is missing or this operation conflicts with an existing record."); }
}
