package com.mediafactory.provider.video;

import java.util.Map;

/**
 * An adapter must authenticate signature, timestamp and body before returning an event.
 */
public interface VideoWebhookVerifier {

  String providerId();

  VerifiedEvent verify(byte[] rawBody, Map<String, String> headers);

  record VerifiedEvent(String eventId, String providerJobId) {

  }
}
