package com.mediafactory.service;
import com.mediafactory.domain.GenerationStatus;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@Service
public class FactoryService {
 private final JdbcClient db;
 public FactoryService(JdbcClient db) { this.db=db; }
 public Map<String,Object> one(String table,UUID id) {
   if(!Set.of("projects","collections","concepts","generations","assets","jobs","quality_reviews").contains(table)) throw new IllegalArgumentException();
   return db.sql("select * from "+table+" where id=:id").param("id",id).query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found"));
 }
 public List<Map<String,Object>> list(String table) {
   if(!Set.of("projects","collections","concepts","generations","assets","jobs","quality_reviews","generation_costs").contains(table)) throw new IllegalArgumentException();
   return db.sql("select * from "+table+" order by created_at desc limit 200").query().listOfRows();
 }
 public Map<String,Object> project(String name,String description) {
   UUID id=UUID.randomUUID(); db.sql("insert into projects(id,name,description) values(?,?,?)").params(id,name,description).update(); return one("projects",id);
 }
 public Map<String,Object> collection(UUID project,String name) {
   one("projects",project); UUID id=UUID.randomUUID(); db.sql("insert into collections(id,project_id,name) values(?,?,?)").params(id,project,name).update(); return one("collections",id);
 }
 public Map<String,Object> concept(UUID collection,String name,String prompt) {
   one("collections",collection); UUID id=UUID.randomUUID(); db.sql("insert into concepts(id,collection_id,name,prompt) values(?,?,?,?)").params(id,collection,name,prompt).update(); return one("concepts",id);
 }
 @Transactional
 public Map<String,Object> generate(UUID concept,String prompt,int width,int height,String key,UUID parent) {
   // Serialize identical request keys across all application instances before checking/replaying.
   db.sql("select pg_advisory_xact_lock(hashtextextended(?,0))").param(key).query().singleRow();
   var existing=db.sql("select g.* from generations g join jobs j on j.generation_id=g.id where j.idempotency_key=?").param(key).query().listOfRows().stream().findFirst();
   if(existing.isPresent()) {
     var row=existing.get();
     if(!row.get("concept_id").equals(concept)||!row.get("prompt").equals(prompt)||((Number)row.get("width")).intValue()!=width||((Number)row.get("height")).intValue()!=height||!Objects.equals(row.get("parent_id"),parent))
       throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key already used with a different request");
     return row;
   }
   one("concepts",concept); UUID id=UUID.randomUUID();
   db.sql("insert into generations(id,concept_id,parent_id,status,prompt,width,height) values(:id,:concept,:parent,'CREATED',:prompt,:width,:height)")
     .param("id",id).param("concept",concept).param("parent",parent).param("prompt",prompt).param("width",width).param("height",height).update();
   transition(id,GenerationStatus.QUEUED);
   db.sql("insert into jobs(id,generation_id,idempotency_key,status) values(?,?,?,'QUEUED')").params(UUID.randomUUID(),id,key).update();
   return one("generations",id);
 }
 public void transition(UUID id,GenerationStatus next) {
   var state=GenerationStatus.valueOf(db.sql("select status from generations where id=? for update").param(id).query(String.class).single());
   if(!state.canTransitionTo(next)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot transition "+state+" to "+next);
   db.sql("update generations set status=?,updated_at=now() where id=?").params(next.name(),id).update();
 }
 @Transactional
 public Map<String,Object> review(UUID asset,String decision,String reason) {
   var a=one("assets",asset); UUID generation=(UUID)a.get("generation_id");
   transition(generation,GenerationStatus.valueOf(decision));
   UUID id=UUID.randomUUID(); db.sql("insert into quality_reviews(id,asset_id,kind,decision,reasons) values(?,?,'HUMAN',?,?)").params(id,asset,decision,reason).update();
   return one("quality_reviews",id);
 }
 @Transactional
 public Map<String,Object> regenerate(UUID asset,String key) {
   var a=one("assets",asset); var g=one("generations",(UUID)a.get("generation_id"));
   return generate((UUID)g.get("concept_id"),(String)g.get("prompt"),((Number)g.get("width")).intValue(),((Number)g.get("height")).intValue(),key,(UUID)g.get("id"));
 }
 @Transactional
 public Map<String,Object> retry(UUID id) {
   var job=db.sql("select * from jobs where id=? for update").param(id).query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
   if(!job.get("status").equals("FAILED")) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only failed jobs can be retried");
   transition((UUID)job.get("generation_id"),GenerationStatus.QUEUED);
   db.sql("update jobs set status='QUEUED', max_attempts=attempts+3, available_at=now(),finished_at=null,updated_at=now() where id=?").param(id).update(); return one("jobs",id);
 }
 public Map<String,Object> dashboard() {
   return db.sql("""
    select (select count(*) from assets where created_at >= date_trunc('day',now() at time zone 'UTC') at time zone 'UTC') as generated_today,
    (select count(*) from quality_reviews where decision='APPROVED' and created_at >= date_trunc('day',now() at time zone 'UTC') at time zone 'UTC') as approved_today,
    (select count(*) from quality_reviews where decision='REJECTED' and created_at >= date_trunc('day',now() at time zone 'UTC') at time zone 'UTC') as rejected_today,
    (select count(*) from generations where status='QA_PENDING') as pending_review,
    (select coalesce(sum(estimated_cost),0) from generation_costs where currency='USD') as generation_cost,
    (select count(*) from jobs where status in ('QUEUED','RUNNING')) as active_jobs,
    (select count(*) from jobs where status='FAILED') as failed_jobs
   """).query().singleRow();
 }
}
