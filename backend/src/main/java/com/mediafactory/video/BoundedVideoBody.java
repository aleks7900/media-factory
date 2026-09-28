package com.mediafactory.video;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Bounds response accumulation before JSON parsing and cancels the transport on overflow. */
final class BoundedVideoBody implements HttpResponse.BodySubscriber<byte[]> {
  final CompletableFuture<byte[]> result=new CompletableFuture<>();
  final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  final int maximum;Flow.Subscription subscription;
  BoundedVideoBody(int maximum){this.maximum=maximum;}
  public CompletionStage<byte[]> getBody(){return result;}
  public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
  public void onNext(List<ByteBuffer> buffers){for(var buffer:buffers){if(bytes.size()+(long)buffer.remaining()>maximum){subscription.cancel();result.completeExceptionally(new VideoFailure("WORKER_RESPONSE_LIMIT"));return;}byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
  public void onError(Throwable error){result.completeExceptionally(error);}
  public void onComplete(){result.complete(bytes.toByteArray());}
}
