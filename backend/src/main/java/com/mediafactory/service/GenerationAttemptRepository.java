package com.mediafactory.service;
import com.mediafactory.provider.*;
import com.mediafactory.provider.ProviderTypes.*;
import com.mediafactory.provider.resilience.ImageGenerationException;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Repository
public class GenerationAttemptRepository {
 public record Attempt(UUID id,UUID generationId,UUID jobId,int number,int providerNumber,String provider,String model) {}
 private final JdbcClient db;private final TransactionTemplate tx;private final PricingService pricing;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 public GenerationAttemptRepository(JdbcClient db,TransactionTemplate tx,PricingService pricing) { this.db=db;this.tx=tx;this.pricing=pricing; }
 public Attempt start(Map<String,Object> job,String provider,String model) {
  return tx.execute(s->{
   var updated=db.sql("update jobs set attempts=attempts+1,provider_attempts=provider_attempts+1,updated_at=now() where id=? and lease_token=? and status='RUNNING' returning attempts,provider_attempts")
    .params(job.get("id"),job.get("lease_token")).query().listOfRows();
   if(updated.isEmpty()) throw new IllegalStateException("Lease lost");
   var counts=updated.getFirst();int number=((Number)counts.get("attempts")).intValue();int providerNumber=((Number)counts.get("provider_attempts")).intValue();
   var attempt=new Attempt(UUID.randomUUID(),(UUID)job.get("generation_id"),(UUID)job.get("id"),number,providerNumber,provider,model);
   var quote=pricing.quote(provider,model,Map.of());
   db.sql("insert into generation_attempts(id,generation_id,job_id,provider,model,attempt_number,status,fallback,estimated_cost,actual_cost,currency) values(?,?,?,?,?,?,'STARTED',?,?,?,?)")
    .params(attempt.id(),attempt.generationId(),attempt.jobId(),provider,model,number,((Number)job.get("route_index")).intValue()>0,quote.estimatedCost(),quote.actualCost(),quote.currency()).update();
   db.sql("update generation_attempts set prompt_snapshot_id=(select id from rendered_prompt_snapshots where generation_id=? and provider=?) where id=?").params(attempt.generationId(),provider,attempt.id()).update();
   db.sql("""
    insert into generation_costs(id,generation_id,job_id,attempt,attempt_id,provider,model,operation,input_usage,output_usage,estimated_cost,actual_cost,currency,outcome,pricing_status,pricing_version)
    values(?,?,?,?,?,?,?,'IMAGE_GENERATION',null,null,?,?,?,'STARTED',?,?)
    """).params(UUID.randomUUID(),attempt.generationId(),attempt.jobId(),number,attempt.id(),provider,model,quote.estimatedCost(),quote.actualCost(),quote.currency(),quote.pricingStatus(),quote.pricingVersion()).update();
   return attempt;
  });
 }
 public PricingService.Quote succeed(Attempt attempt,Result<Media> result,long duration) {
  var usage=new LinkedHashMap<String,String>();
  for(String k:List.of("inputTokens","outputTokens","textInputTokens","imageInputTokens")) if(result.metadata().containsKey(k)) usage.put(k,result.metadata().get(k));
  var quote=pricing.quote(attempt.provider(),attempt.model(),usage);
  tx.executeWithoutResult(s->{
   db.sql("update generation_attempts set status='SUCCEEDED',completed_at=now(),duration_ms=?,provider_request_id=?,estimated_cost=?,actual_cost=?,currency=?,metadata=cast(? as jsonb) where id=? and status='STARTED'")
    .params(duration,result.metadata().get("providerRequestId"),quote.estimatedCost(),quote.actualCost(),quote.currency(),JSON.writeValueAsString(result.metadata()),attempt.id()).update();
   db.sql("update generation_costs set outcome='SUCCEEDED',input_usage=?,output_usage=?,estimated_cost=?,actual_cost=?,currency=?,pricing_status=?,pricing_version=?,usage_details=cast(? as jsonb) where attempt_id=?")
    .params(usage.containsKey("inputTokens")?Long.valueOf(usage.get("inputTokens")):attempt.provider().equals("mock")?Long.valueOf(result.usage().inputUsage()):null,usage.containsKey("outputTokens")?Long.valueOf(usage.get("outputTokens")):attempt.provider().equals("mock")?Long.valueOf(result.usage().outputUsage()):null,
     quote.estimatedCost(),quote.actualCost(),quote.currency(),quote.pricingStatus(),quote.pricingVersion(),JSON.writeValueAsString(usage),attempt.id()).update();
  });return quote;
 }
 public void fail(Attempt attempt,ImageGenerationException error,long duration) {
  String status=switch(error.type()) { case RATE_LIMIT -> "RATE_LIMITED";case TIMEOUT -> "TIMED_OUT";default -> "FAILED"; };
  tx.executeWithoutResult(s->{
   db.sql("update generation_attempts set status=?,completed_at=now(),duration_ms=?,provider_request_id=?,error_type=?,error_code=?,error_message=?,retryable=?,outcome_unknown=? where id=? and status='STARTED'")
    .params(status,duration,error.requestId(),error.type().name(),error.type().name(),error.getMessage(),error.retryable(),error.outcomeUnknown(),attempt.id()).update();
   db.sql("update generation_costs set outcome='FAILED' where attempt_id=?").param(attempt.id()).update();
  });
 }
}
