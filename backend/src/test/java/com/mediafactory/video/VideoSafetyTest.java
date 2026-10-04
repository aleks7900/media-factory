package com.mediafactory.video;

import static org.assertj.core.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class VideoSafetyTest {

  @Test
  void oversizedWorkerResponseCancelsTransport() {
    var body = new BoundedVideoBody(3);
    boolean[] cancelled = {false};
    body.onSubscribe(new Flow.Subscription() {
      public void request(long n) {
      }

      public void cancel() {
        cancelled[0] = true;
      }
    });
    body.onNext(List.of(ByteBuffer.wrap(new byte[4])));
    assertThat(cancelled[0]).isTrue();
    assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void motionTextTypesAreValidated() {
    assertThatThrownBy(
        () -> new MotionPlanner().plan(Map.of(), Map.of("subjectMotion", 123))).isInstanceOf(
        IllegalArgumentException.class);
  }

  @Test
  void motionLightingParticlesAndDepthReachPrompt() {
    var planner = new MotionPlanner();
    var plan = planner.plan(Map.of(),
        Map.of("depthMotion", "gentle parallax", "lightingMotion", "stable moonlight",
            "particleMotion", "subtle dust"));
    assertThat(planner.variables(plan).get("environment_motion").toString()).contains(
        "gentle parallax", "stable moonlight", "subtle dust");
  }

  @Test
  void riskyAndDirectionalReversalRequireExplicitControls() {
    var planner = new MotionPlanner();
    assertThatThrownBy(() -> planner.plan(Map.of(), Map.of("cameraMotion", "ORBIT"))).isInstanceOf(
        IllegalArgumentException.class);
    assertThatThrownBy(() -> planner.plan(Map.of(),
        Map.of("cameraMotion", "PAN_LEFT", "reversible", true))).isInstanceOf(
        IllegalArgumentException.class);
  }
}
