package com.mediafactory.video;

public class VideoFailure extends RuntimeException {

  public VideoFailure(String code) {
    super(code);
  }
}
