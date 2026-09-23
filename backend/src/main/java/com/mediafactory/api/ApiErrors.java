package com.mediafactory.api;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataIntegrityViolationException;
@RestControllerAdvice
public class ApiErrors {
 @ExceptionHandler(DataIntegrityViolationException.class)
 public ProblemDetail conflict(DataIntegrityViolationException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"A referenced record is missing or this operation conflicts with an existing record."); }
}
