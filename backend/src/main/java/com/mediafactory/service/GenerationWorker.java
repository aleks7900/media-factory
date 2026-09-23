package com.mediafactory.service;
import com.mediafactory.provider.ImageGenerationProvider;
import com.mediafactory.provider.ProviderTypes.*;
import com.mediafactory.storage.MediaStorage;
import com.mediafactory.quality.TechnicalQa;
import com.mediafactory.domain.GenerationStatus;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name="media.worker.enabled",havingValue="true",matchIfMissing=true)
public class GenerationWorker {
 private final JdbcClient db; private final TransactionTemplate tx; private final FactoryService service;
 private final ImageGenerationProvider provider; private final MediaStorage storage; private final TechnicalQa qa;
 public GenerationWorker(JdbcClient db,TransactionTemplate tx,FactoryService service,ImageGenerationProvider provider,MediaStorage storage,TechnicalQa qa) {
   this.db=db;this.tx=tx;this.service=service;this.provider=provider;this.storage=storage;this.qa=qa;
 }
 @Scheduled(fixedDelay=1500)
 public void tick() {
   try { recover(); var job=claim(); if(job!=null) execute(job); }
   catch(Exception e) { LoggerFactory.getLogger(getClass()).error("Worker cycle failed",e); }
 }
 public void recover() {
   tx.executeWithoutResult(s -> {
     var expired=db.sql("select * from jobs where status='RUNNING' and locked_at<now()-interval '5 minutes' for update skip locked").query().listOfRows();
     for(var j:expired) fail(j,"Worker lease expired");
   });
 }
 public Map<String,Object> claim() {
   return tx.execute(s -> {
     var next=db.sql("select * from jobs where status='QUEUED' and available_at<=now() order by created_at for update skip locked limit 1").query().listOfRows().stream().findFirst();
     if(next.isEmpty()) return null;
     var j=next.get(); UUID token=UUID.randomUUID();
     db.sql("update jobs set status='RUNNING',attempts=attempts+1,locked_at=now(),lease_token=?,updated_at=now() where id=?").params(token,j.get("id")).update();
     service.transition((UUID)j.get("generation_id"),GenerationStatus.GENERATING);
     return service.one("jobs",(UUID)j.get("id"));
   });
 }
 public void execute(Map<String,Object> job) {
   try {
     var g=service.one("generations",(UUID)job.get("generation_id"));
     int width=((Number)g.get("width")).intValue(),height=((Number)g.get("height")).intValue();
     // Stable operation ID allows a future adapter to replay an external result after lease recovery.
     var request=new Request(g.get("id").toString(),(String)g.get("prompt"),width,height);
     var estimate=provider.estimate(request);
     db.sql("""
       insert into generation_costs(id,generation_id,job_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency,outcome)
       values(?,?,?,?,?,?,?,?,?,?,?,'STARTED') on conflict(job_id,attempt,operation) do nothing
       """).params(UUID.randomUUID(),g.get("id"),job.get("id"),job.get("attempts"),estimate.provider(),estimate.model(),estimate.operation(),estimate.inputUsage(),estimate.outputUsage(),estimate.estimatedCost(),estimate.currency()).update();
     Result<Media> result=provider.generate(request);
     var usage=result.usage();
     db.sql("update generation_costs set provider=?,model=?,operation=?,input_usage=?,output_usage=?,estimated_cost=?,currency=?,outcome='SUCCEEDED' where job_id=? and attempt=? and operation=?")
       .params(usage.provider(),usage.model(),usage.operation(),usage.inputUsage(),usage.outputUsage(),usage.estimatedCost(),usage.currency(),job.get("id"),job.get("attempts"),estimate.operation()).update();
     byte[] bytes=result.output().bytes();
     String checksum=TechnicalQa.checksum(bytes);
     UUID assetId=UUID.randomUUID(); String key="originals/"+g.get("id")+"/"+assetId+".png";
     storage.putOriginal(key,bytes,result.output().contentType());
     tx.executeWithoutResult(s -> {
       if(!ownsLease(job)) return; // Late results may leave an immutable orphan; never change a newer attempt.
       db.sql("select pg_advisory_xact_lock(hashtextextended(?,0))").param(checksum).query().singleRow();
       boolean duplicate=db.sql("select exists(select 1 from assets where sha256=?)").param(checksum).query(Boolean.class).single();
       var report=qa.inspect(bytes,width,height,duplicate);
       service.transition((UUID)g.get("id"),GenerationStatus.GENERATED);
       db.sql("insert into assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height) values(?,?,?,?,?,?,?,?)")
         .params(assetId,g.get("id"),key,checksum,result.output().contentType(),bytes.length,report.width(),report.height()).update();
       service.transition((UUID)g.get("id"),GenerationStatus.QA_PENDING);
       db.sql("insert into quality_reviews(id,asset_id,kind,decision,reasons) values(?,?,'TECHNICAL',?,?)")
         .params(UUID.randomUUID(),assetId,report.passed()?"PASSED":"REJECTED",String.join("; ",report.failures())).update();
       if(!report.passed()) service.transition((UUID)g.get("id"),GenerationStatus.REJECTED);
       db.sql("update jobs set status='SUCCEEDED',failure_reason=null,provider_metadata=cast(? as jsonb),finished_at=now(),updated_at=now(),lease_token=null where id=?")
         .params(JsonMapper.builder().build().writeValueAsString(Map.of("provider",usage.provider(),"model",usage.model(),"details",result.metadata())),job.get("id")).update();
     });
   } catch(Exception e) {
     db.sql("update generation_costs set outcome='FAILED' where job_id=? and attempt=? and outcome='STARTED'")
       .params(job.get("id"),job.get("attempts")).update();
     LoggerFactory.getLogger(getClass()).warn("Generation job {} failed",job.get("id"),e);
     tx.executeWithoutResult(s -> { if(ownsLease(job)) fail(job,e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),"Operation failed")); });
   }
 }
 private boolean ownsLease(Map<String,Object> job) {
   var row=db.sql("select status,lease_token from jobs where id=? for update").param(job.get("id")).query().singleRow();
   return row.get("status").equals("RUNNING") && Objects.equals(row.get("lease_token"),job.get("lease_token"));
 }
 private void fail(Map<String,Object> j,String reason) {
   db.sql("update generation_costs set outcome='FAILED' where job_id=? and attempt=? and outcome='STARTED'")
     .params(j.get("id"),j.get("attempts")).update();
   int attempts=((Number)j.get("attempts")).intValue(); boolean retry=attempts<((Number)j.get("max_attempts")).intValue();
   service.transition((UUID)j.get("generation_id"),retry?GenerationStatus.QUEUED:GenerationStatus.FAILED);
   db.sql("update jobs set status=?,failure_reason=?,available_at=now()+(? * interval '1 second'),lease_token=null,finished_at=case when ? then null else now() end,updated_at=now() where id=?")
     .params(retry?"QUEUED":"FAILED",reason.substring(0,Math.min(reason.length(),2000)),Math.min(300,5*(1<<Math.min(attempts,6))),retry,j.get("id")).update();
 }
}
