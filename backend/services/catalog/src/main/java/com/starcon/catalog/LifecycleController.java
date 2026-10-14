package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.*;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/** 3.1.1.3 SPU 生命周期：状态流转、变更历史与批量规则。权限沿用商品档案页的 catalog.spu.* 。 */
@RestController
@RequestMapping("/catalog")
public class LifecycleController {
  private final AccessService access;
  private final LifecycleService service;

  public LifecycleController(AccessService access, LifecycleService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  @GetMapping("/products/{id}/lifecycle")
  public LifecycleView view(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.view(id));
  }

  @PostMapping("/products/{id}/lifecycle")
  public LifecycleEvent transition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody TransitionInput input) {
    return call(auth, "spu.update", a -> service.transition(a, id, input, null));
  }

  @GetMapping("/lifecycle/metrics")
  public List<MetricInfo> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.metrics());
  }

  @GetMapping("/lifecycle/rules")
  public List<LifecycleRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.rules());
  }

  @PostMapping("/lifecycle/rules")
  public UUID ruleCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RuleInput input) {
    return call(auth, "spu.create", a -> service.saveRule(a, null, input));
  }

  @PutMapping("/lifecycle/rules/{id}")
  public UUID ruleUpdate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody RuleInput input) {
    return call(auth, "spu.update", a -> service.saveRule(a, id, input));
  }

  @DeleteMapping("/lifecycle/rules/{id}")
  public void ruleDelete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.delete",
        a -> {
          service.deleteRule(a, id, version);
          return null;
        });
  }

  @GetMapping("/lifecycle/rules/{id}/preview")
  public RulePreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String cursor) {
    return call(auth, "spu.read", a -> service.preview(id, limit, cursor));
  }

  @PostMapping("/lifecycle/rules/{id}/run")
  public RuleRun run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody RuleInputScope scope) {
    return call(
        auth,
        "spu.update",
        a ->
            service.run(
                a,
                new RuleInputScope(
                    scope.ruleId() == null ? id : scope.ruleId(),
                    scope.reason(),
                    scope.occurredAt(),
                    scope.limit())));
  }
}
