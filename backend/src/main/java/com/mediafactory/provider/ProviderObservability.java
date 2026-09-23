package com.mediafactory.provider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;

@Component
public class ProviderObservability {
 private static final Logger LOG=LoggerFactory.getLogger(ProviderObservability.class);
 private final MeterRegistry meters;
 public ProviderObservability(MeterRegistry meters) { this.meters=meters; }
 public void event(String event,UUID generation,UUID attempt,String provider,String model,long duration) {
  LOG.atInfo().addKeyValue("event",event).addKeyValue("generationId",generation).addKeyValue("attemptId",attempt)
   .addKeyValue("provider",provider).addKeyValue("model",model).addKeyValue("durationMs",duration).log("Media generation event");
 }
 public void count(String metric,String provider,String model) { meters.counter("media_factory_"+metric,"provider",provider,"model",model).increment(); }
 public void duration(String provider,String model,long millis) { meters.timer("media_factory_provider_request_duration","provider",provider,"model",model).record(millis,TimeUnit.MILLISECONDS); }
 public void cost(String provider,String model,BigDecimal amount,String currency) { if(amount!=null) meters.summary("media_factory_generation_cost","provider",provider,"model",model,"currency",currency).record(amount.doubleValue()); }
}
