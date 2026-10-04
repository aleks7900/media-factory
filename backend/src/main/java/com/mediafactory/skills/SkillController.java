package com.mediafactory.skills;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

  final SkillExecutionService service;

  public SkillController(SkillExecutionService service) {
    this.service = service;
  }

  @GetMapping
  public Object catalog() {
    return SkillPlanService.SKILLS.stream()
        .sorted()
        .map(s -> Map.of("name", s, "version", 1))
        .toList();
  }

  @GetMapping("/research/{id}")
  public Object research(@PathVariable UUID id) {
    return service
        .db
        .sql("select c.* from trend_candidates c where research_run_id=? order by name,id")
        .param(id)
        .query()
        .listOfRows()
        .stream()
        .map(SkillExecutionService::json)
        .toList();
  }

  @PostMapping("/executions")
  public Object plan(@RequestBody SkillExecutionService.Request r) {
    return service.plan(r);
  }

  @GetMapping("/executions")
  public Object list(@RequestParam UUID projectId, @RequestParam(defaultValue = "0") int page) {
    return service.list(projectId, page);
  }

  @GetMapping("/executions/{id}")
  public Object detail(@PathVariable UUID id) {
    return service.detail(id);
  }

  @PostMapping("/executions/{id}/{action:start|resume|approve|cancel}")
  public Object action(
      @PathVariable UUID id, @PathVariable String action, @RequestBody Map<String, String> body) {
    return service.action(id, action, body.get("reason"));
  }
}
