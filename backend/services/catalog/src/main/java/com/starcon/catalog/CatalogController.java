package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.*;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/catalog")
public class CatalogController {
  private final AccessService access;
  private final CatalogService service;

  public CatalogController(AccessService access, CatalogService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  @GetMapping("/products")
  public List<Product> list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "") String q) {
    return call(auth, "spu.read", a -> service.list(q));
  }

  @GetMapping("/products/{id}")
  public Detail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.readDetail(id));
  }

  @PostMapping("/products")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SpuInput input) {
    return call(auth, "spu.create", a -> service.saveSpu(a, null, input));
  }

  @PutMapping("/products/{id}")
  public UUID update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SpuInput input) {
    return call(auth, "spu.update", a -> service.saveSpu(a, id, input));
  }

  @DeleteMapping("/products/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.delete",
        a -> {
          service.deleteSpu(a, id, version);
          return null;
        });
  }

  @PostMapping("/products/{id}/attributes")
  public void attributeCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AttributeInput input) {
    call(
        auth,
        "attribute.create",
        a -> {
          service.saveAttribute(a, id, null, input);
          return null;
        });
  }

  @PutMapping("/products/{id}/attributes/{aid}")
  public void attributeUpdate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID aid,
      @Valid @RequestBody AttributeInput input) {
    call(
        auth,
        "attribute.update",
        a -> {
          service.saveAttribute(a, id, aid, input);
          return null;
        });
  }

  @DeleteMapping("/products/{id}/attributes/{aid}")
  public void attributeDelete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID aid,
      @RequestParam long version) {
    call(
        auth,
        "attribute.delete",
        a -> {
          service.deleteAttribute(a, id, aid, version);
          return null;
        });
  }

  @PostMapping("/products/{id}/skus")
  public UUID skuCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SkuInput input) {
    return call(auth, "sku.create", a -> service.saveSku(a, id, null, input));
  }

  @PutMapping("/products/{id}/skus/{sid}")
  public UUID skuUpdate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sid,
      @Valid @RequestBody SkuInput input) {
    return call(auth, "sku.update", a -> service.saveSku(a, id, sid, input));
  }

  @DeleteMapping("/products/{id}/skus/{sid}")
  public void skuDelete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sid,
      @RequestParam long version) {
    call(
        auth,
        "sku.delete",
        a -> {
          service.deleteSku(a, id, sid, version);
          return null;
        });
  }

  @GetMapping("/records/{kind}")
  public List<ProductRecord> records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String kind) {
    return call(auth, kind + ".read", a -> service.records(kind));
  }

  @PostMapping("/records/{kind}")
  public UUID recordCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String kind,
      @Valid @RequestBody RecordInput input) {
    return call(auth, kind + ".create", a -> service.saveRecord(a, kind, null, input));
  }

  @PutMapping("/records/{kind}/{id}")
  public UUID recordUpdate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String kind,
      @PathVariable UUID id,
      @Valid @RequestBody RecordInput input) {
    return call(auth, kind + ".update", a -> service.saveRecord(a, kind, id, input));
  }

  @DeleteMapping("/records/{kind}/{id}")
  public void recordDelete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String kind,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        kind + ".delete",
        a -> {
          service.deleteRecord(a, kind, id, version);
          return null;
        });
  }
}
