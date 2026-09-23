package com.mediafactory.service;
import com.mediafactory.provider.*;
import com.mediafactory.provider.routing.ImageProviderRouter;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.*;
@Service
public class ProviderInfoService {
 private final JdbcClient db;private final ImageProviderRouter router;private final ImageGenerationProperties properties;
 public ProviderInfoService(JdbcClient db,ImageProviderRouter router,ImageGenerationProperties properties) {this.db=db;this.router=router;this.properties=properties;}
 public List<Map<String,Object>> list() {
  var result=new ArrayList<Map<String,Object>>();
  for(var adapter:router.all()) {
   String id=adapter.providerId();if(!properties.providers().containsKey(id)) continue;var config=properties.provider(id);
   var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("name",id.equals("mock")?"Mock Studio":id);
   boolean enabled=config.enabled()&&adapter.configured();row.put("enabled",enabled);
   String health=db.sql("select health from provider_runtime where provider=?").param(id).query(String.class).optional().orElse("HEALTHY");
   row.put("health",enabled?health:"DISABLED");row.put("defaultModel",config.model());row.put("model",config.model());row.put("models",config.models());
   row.put("default",properties.defaultProvider().equals(id));row.put("environment",properties.environment());row.put("capabilities",adapter.capabilities());
   row.put("requestsPerMinute",config.rateLimit().requestsPerMinute());row.put("maxConcurrent",config.rateLimit().concurrentRequests());
   row.put("stats",db.sql("""
    select count(*) as requests_today,
     coalesce(avg(case when status='SUCCEEDED' then 100.0 else 0.0 end),0) as success_rate,
     coalesce(avg(duration_ms),0) as average_latency_ms,
     sum(estimated_cost) filter(where currency='USD') as estimated_cost_today,
     count(*) filter(where estimated_cost is null) as unknown_cost_attempts
    from generation_attempts where provider=? and started_at >= date_trunc('day',now() at time zone 'UTC') at time zone 'UTC'
    """).param(id).query().singleRow());
   result.add(row);
  }return result;
 }
}
