package com.mediafactory.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail invalid(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(com.mediafactory.prompt.PromptException.class)
  public ProblemDetail prompt(com.mediafactory.prompt.PromptException e) {
    var p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    p.setProperty("code", e.code());
      if (e.variable() != null) {
          p.setProperty("variable", e.variable());
      }
    return p;
  }

  @ExceptionHandler(com.mediafactory.provider.resilience.ImageGenerationException.class)
  public ProblemDetail provider(com.mediafactory.provider.resilience.ImageGenerationException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public ProblemDetail status(org.springframework.web.server.ResponseStatusException e) {
    return ProblemDetail.forStatusAndDetail(e.getStatusCode(),
        e.getReason() == null ? "Request failed" : e.getReason());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail conflict(DataIntegrityViolationException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
        "A referenced record is missing or this operation conflicts with an existing record.");
  }
}
