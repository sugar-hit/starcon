package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.1.3 采购计划读 warehouse 的**仓 / 法人参照**的跨服务只读端口。
 *
 * <p>蓝图本模块的职责句写死「<b>按仓库</b>制定采购计划」，输出句写死「计划行：SKU、数量、
 * <b>目标仓</b>、期望到货日、优先级」，输入句写死「<b>按店铺大促节奏</b>」。仓库与法人都是
 * warehouse 服务的事实（{@code warehouse} / {@code legal_entity} 登记表在它的库里），
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 warehouse 的
 * 授权只读端点</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 {@link StockoutInventoryPort}（读 warehouse 的库存）、{@link ReplenishmentCatalogPort}
 * （读 catalog 的报价与箱规）逐字同构 —— 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>。
 *
 * <p>用两个已交付形状：
 *
 * <ul>
 *   <li>{@code GET /warehouse/warehouses} —— 仓库参照清单（id / code / name / status）。</li>
 *   <li>{@code GET /warehouse/legal-entities} —— 法人参照清单。法人是**锁库预占的必填项**
 *       （warehouse 7.2.1.1 的 {@code legal_entity_id} NOT NULL）—— 货权落在哪个法人上，
 *       预占就得记在哪个法人上。</li>
 * </ul>
 *
 * <p><b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （trade 侧 RLS + warehouse 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p><b>取不到就显式失败</b>（503），绝不返回空集合冒充「这个租户没有仓」—— 那会让
 * 建计划时的目标仓下拉变成空白，而真实情况是仓库服务挂了。
 */
@Service
public class PurchasePlanWarehousePort {

  /** warehouse 的仓库参照行（只声明本模块真正用到的字段）。 */
  record WarehouseView(UUID id, String code, String name, String countryCode, String status) {}

  /** warehouse 的法人参照行（只声明本模块真正用到的字段）。 */
  record LegalEntityView(UUID id, String code, String name, String status) {}

  /** 端口专用的 ObjectMapper：**忽略上游多出来的字段**（warehouse 的响应比这里声明的多）。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public PurchasePlanWarehousePort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：建计划时要翻仓与法人清单，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时仓库一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  /** 仓库参照清单（7.x 的参照下拉）。 */
  public List<WarehouseView> warehouses(String bearer) {
    List<WarehouseView> rows;
    try {
      rows =
          client
              .get()
              .uri("/warehouse/warehouses")
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<WarehouseView>>() {});
    } catch (RestClientResponseException e) {
      throw upstream(e.getStatusCode().value(), "读仓库清单");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
    return rows == null ? List.of() : rows;
  }

  /** 法人参照清单。 */
  public List<LegalEntityView> legalEntities(String bearer) {
    List<LegalEntityView> rows;
    try {
      rows =
          client
              .get()
              .uri("/warehouse/legal-entities")
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<LegalEntityView>>() {});
    } catch (RestClientResponseException e) {
      throw upstream(e.getStatusCode().value(), "读法人清单");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
    return rows == null ? List.of() : rows;
  }

  private ResponseStatusException upstream(int status, String what) {
    if (status == HttpStatus.UNAUTHORIZED.value()) {
      return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    if (status == HttpStatus.FORBIDDEN.value()) {
      return new ResponseStatusException(
          HttpStatus.FORBIDDEN, "当前身份无权读取仓库参照，采购计划暂时解析不了目标仓与法人");
    }
    return unavailable("仓库服务返回 " + status + "（读取" + what + "时）");
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：目标仓与法人的事实来源是仓库服务的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空造一个仓或法人出来");
  }
}
