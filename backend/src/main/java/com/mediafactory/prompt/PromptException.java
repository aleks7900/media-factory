package com.mediafactory.prompt;
public class PromptException extends RuntimeException {
 private final String code;private final String variable;
 public PromptException(String code,String variable,String message) {super(message);this.code=code;this.variable=variable;}
 public String code(){return code;} public String variable(){return variable;}
 public static PromptException invalid(String message){return new PromptException("PROMPT_INVALID",null,message);}
}
