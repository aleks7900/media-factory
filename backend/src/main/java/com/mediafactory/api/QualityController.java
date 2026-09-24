package com.mediafactory.api;
import com.mediafactory.quality.*;
import com.mediafactory.quality.QualityModels.*;
import com.mediafactory.quality.QualityReviewService.HumanCommand;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class QualityController {
 private final QualityReviewService reviews;private final ReviewWorkflowService workflow;private final QaConfiguration config;private final ReviewActor actor;private final JdbcClient db;
 public QualityController(QualityReviewService reviews,ReviewWorkflowService workflow,QaConfiguration config,ReviewActor actor,JdbcClient db){this.reviews=reviews;this.workflow=workflow;this.config=config;this.actor=actor;this.db=db;}
 @GetMapping({"/reviews","/reviews/queue"}) public Object queue(@RequestParam Map<String,String> filters,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="24") int size){return reviews.queue(filters,page,size);}
 @GetMapping("/reviews/{id}") public Object detail(@PathVariable UUID id){return reviews.detail(id);}
 @PostMapping("/reviews/{id}/approve") public Object approve(@PathVariable UUID id,@RequestBody HumanCommand request){return reviews.decide(id,Decision.APPROVED,request,actor.current());}
 @PostMapping("/reviews/{id}/reject") public Object reject(@PathVariable UUID id,@RequestBody HumanCommand request){return reviews.decide(id,Decision.REJECTED,request,actor.current());}
 @PostMapping("/reviews/{id}/regenerate") public Object regenerate(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody ReviewWorkflowService.Regenerate request){return workflow.regenerate(id,key,request);}
 public record Rerun(int revision,String mockScenario){}
 @PostMapping("/reviews/{id}/rerun") public Object rerun(@PathVariable UUID id,@RequestBody Rerun request){return workflow.rerun(id,request.revision(),request.mockScenario());}
 public record AddedFinding(int revision,Finding finding){}
 @PostMapping("/reviews/{id}/findings") public Object finding(@PathVariable UUID id,@RequestBody AddedFinding request){return reviews.addFinding(id,request.revision(),request.finding(),actor.current());}
 public record BatchItem(@NotNull UUID id,@Min(0) int revision,String reasonCode,@Size(max=4000) String reasonText){}
 public record Batch(@NotNull @Size(min=1,max=100) List<@Valid BatchItem> items,@NotNull Decision decision){}
 @PostMapping("/reviews/batch") public Object batch(@Valid @RequestBody Batch request){
  var results=new ArrayList<Map<String,Object>>();for(var item:request.items())try{reviews.decide(item.id(),request.decision(),new HumanCommand(item.revision(),item.reasonCode(),item.reasonText()),actor.current());results.add(Map.of("id",item.id(),"success",true));}
  catch(ResponseStatusException e){results.add(Map.of("id",item.id(),"success",false,"status",e.getStatusCode().value(),"error",Objects.toString(e.getReason(),"Review conflict")));}
  catch(IllegalArgumentException e){results.add(Map.of("id",item.id(),"success",false,"status",400,"error",e.getMessage()));}
  return Map.of("results",results);
 }
 @GetMapping("/qa/policies") public Object policies(){return config.policies();}
 @GetMapping("/qa/dashboard") public Object dashboard(){return reviews.dashboard();}
 @GetMapping("/qa/jobs") public Object jobs(){return db.sql("select * from qa_jobs order by created_at desc limit 200").query().listOfRows();}
 public record PolicyRequest(@NotBlank String policyId){}
 @PutMapping("/collections/{id}/qa-policy") public Object policy(@PathVariable UUID id,@Valid @RequestBody PolicyRequest request){config.policy(request.policyId());if(db.sql("update collections set qa_policy=? where id=?").params(request.policyId(),id).update()!=1)throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);return Map.of("id",id,"qaPolicy",request.policyId());}
 public record PublicationRequest(@NotNull UUID assetId,@NotBlank @Size(max=100) String channel,@Size(max=2000) String externalId){}
 @PostMapping("/publications") public Object publish(@Valid @RequestBody PublicationRequest request){return workflow.publish(request.assetId(),request.channel(),request.externalId());}
}
