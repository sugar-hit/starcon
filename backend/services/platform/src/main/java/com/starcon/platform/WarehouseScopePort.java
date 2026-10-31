package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * warehouse 服务（端口 8084）的**跨服务只读端口**：仓库清单。
 *
 * <p>蓝图 18.3.1.1 的启用范围里有「仓库」这一档 ⇒ 运营要能挑到**真实的**仓库，
 * 而不是自己敲一个 uuid（敲错了就是一条永远不生效的范围）。
 *
 * <p>⚠️ <b>只读**：本服务**不**回写 warehouse 的库（AGENTS.md：禁止跨服务 SQL / FK）。
 * 规则档案的范围行里只存**只读引用**（仓库 id），不建跨库外键、不抄仓库档案。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户
 * 并强制 RLS。租户隔离是<b>两道</b>（platform 侧 RLS + warehouse 侧 RLS）。
 *
 * <p>⚠️ <b>取不到就 503</b>：空集合会让人以为「这个租户没有仓库」，
 * 而真相是「读不到」（见 {@link PlatformUpstreamClient} 的同一纪律）。
 */
@Service
public class WarehouseScopePort {

  private final PlatformUpstreamClient client;

  public WarehouseScopePort(
      @Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    this.client = PlatformUpstreamClient.to("warehouse 服务", url);
  }

  /** 上游一个仓库。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WarehouseRow(
      UUID id, String code, String name, String countryCode, String status, boolean isDefault) {}

  /**
   * 仓库清单。
   *
   * <p>⚠️ 上游返回的是**裸 JSON 数组**，不是分页对象 —— 按数组类型反序列化
   * （裸数组被拆成 {@code List<Object>} 的话字段会全丢）。
   */
  public List<WarehouseRow> warehouses(String bearer) {
    return Arrays.stream(
            client.get(
                uriBuilder -> uriBuilder.path("/warehouse/warehouses"), bearer, WarehouseRow[].class))
        .map(
            r ->
                new WarehouseRow(
                    r.id(), r.code(), r.name(), r.countryCode(), r.status(), r.isDefault()))
        .toList();
  }
}
