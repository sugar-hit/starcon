package com.starcon.identity;

import static com.starcon.identity.DefaultContextModels.DefaultContextWarehouseOption;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 1.4.2.2 默认店铺与仓库 —— <b>仓库清单</b>的跨服务只读端口（读 08 仓储作业）。
 *
 * <p><b>为什么走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：
 * 仓库（{@code warehouse}）在 warehouse 服务的库里，默认上下文在 identity 服务。
 * 本服务<b>只</b>通过 warehouse 的授权只读端点 {@code GET /warehouse/facilities} 取数，
 * <b>identity 库里不存在任何仓库副本</b>。
 *
 * <p><b>为什么用 {@code /warehouse/facilities} 而不是 {@code /warehouse/warehouses}</b>：
 * 后者是 7.1.x 的参照下拉，它的权限闸写的是 {@code inventory.read}；
 * 而 identity 侧登记的库存页权限码是 {@code warehouse.inventory.read}
 * （V8 注册的是 {@code warehouse.} 前缀的一族码），两者对不上 ⇒ 用前者会恒定 403，
 * 仓库维度会变成一个永远空的选择器。前者用的是 {@code warehouse.facilities.read}
 * （V69 注册、OWNER 默认带），是仓库档案的**真实**清单，且带 {@code is_default} 标记 ——
 * 「默认仓库」的回落来源就是它。
 *
 * <p><b>真分页要翻完</b>：上游是分页接口，本端口按页取到取完为止
 * （上限 {@value #MAX_PAGES} 页 × {@value #PAGE_SIZE} 条），否则默认上下文会只认第一页。
 *
 * <p><b>上游不可达 ≠ 上游没有仓库</b>：取不到时抛 {@link Unavailable}，
 * 由服务层翻成「该维度读不到」并如实报出原因，绝不返回空清单冒充「这个租户没有仓库」。
 */
public interface DefaultContextWarehouseDirectoryPort {

  /** 仓库档案分页响应（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FacilityPage(List<WarehouseRow> rows, long total, int page, int size) {}

  /** 仓库行（{@code is_default} 在上游叫 {@code defaultWarehouse}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record WarehouseRow(
      UUID id, String code, String name, String status, boolean defaultWarehouse) {}

  /** 每页条数（与上游 MAX_PAGE_SIZE 同量级）。 */
  int PAGE_SIZE = 200;

  /** 最多翻几页（防御：上游总数异常大时不至于把请求线程占住）。 */
  int MAX_PAGES = 20;

  /**
   * 取当前用户可见的仓库清单（翻完所有分页）。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 warehouse</b>）
   * @throws Unavailable 上游不可达 / 被拒绝 / 返回体不认
   */
  List<DefaultContextWarehouseOption> warehouses(String bearer);

  /** 上游读不到。服务层把它翻成「该维度读不到」，而不是「没有仓库」。 */
  class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }

    public Unavailable(String message) {
      super(message);
    }
  }

  /** 真实的 HTTP 实现。 */
  class HttpDefaultContextWarehouseDirectoryPort implements DefaultContextWarehouseDirectoryPort {

    /** warehouse 的仓库档案出口（8.1.1.1 仓库列表）。 */
    static final String FACILITIES_PATH = "/warehouse/facilities";

    private final RestClient client;

    public HttpDefaultContextWarehouseDirectoryPort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
      factory.setReadTimeout(Duration.ofSeconds(8));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public List<DefaultContextWarehouseOption> warehouses(String bearer) {
      List<DefaultContextWarehouseOption> all = new ArrayList<>();
      for (int page = 0; page < MAX_PAGES; page++) {
        FacilityPage response = fetch(bearer, page);
        if (response == null || response.rows() == null) {
          throw new Unavailable("warehouse 服务没有返回仓库清单");
        }
        for (WarehouseRow row : response.rows()) {
          if (row.id() == null) continue;
          all.add(
              new DefaultContextWarehouseOption(
                  row.id(), row.code(), row.name(), row.status(), row.defaultWarehouse()));
        }
        int size = response.size() <= 0 ? PAGE_SIZE : response.size();
        if (response.rows().size() < size) return all;
        if ((long) (page + 1) * size >= response.total()) return all;
      }
      return all;
    }

    private FacilityPage fetch(String bearer, int page) {
      try {
        FacilityPage response =
            client
                .get()
                .uri(uriBuilder -> uriBuilder.path(FACILITIES_PATH).queryParam("page", page)
                    .queryParam("size", PAGE_SIZE)
                    .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(FacilityPage.class);
        if (response == null) throw new Unavailable("warehouse 服务没有返回仓库清单");
        return response;
      } catch (RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401 || code == 403) {
          throw new Unavailable(
              "当前角色没有仓库读取权限（warehouse 拒绝：" + code + "）：仓库清单读不到", e);
        }
        throw new Unavailable("warehouse 服务返回 " + code + "：仓库清单读不到", e);
      } catch (RestClientException e) {
        throw new Unavailable("warehouse 服务不可达：仓库清单读不到", e);
      }
    }
  }

  /** 「上游没配」的实现（刻意不是空列表：空清单与「真的没有仓库」在界面上长得一样）。 */
  class UnwiredDefaultContextWarehouseDirectoryPort
      implements DefaultContextWarehouseDirectoryPort {

    @Override
    public List<DefaultContextWarehouseOption> warehouses(String bearer) {
      throw new Unavailable(
          "仓储服务地址未配置（WAREHOUSE_URL 为空）：仓库清单的唯一来源是 warehouse 的"
              + "授权只读端点 GET /warehouse/facilities（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
              + "本服务不会凭空报一个仓库清单。请配置 WAREHOUSE_URL 后重试");
    }
  }
}
