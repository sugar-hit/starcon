package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5.2.2.1 汇率查询：读取租户**本位币**的跨服务端口。
 *
 * <p>蓝图职责句写的是「查询各币种对**各本位币**的汇率」—— 所以本模块要知道本位币是什么。
 * 而本位币的归属在 17.1.2.2：**本位币归法人**，由 identity 服务的法人档案持有
 * （{@code legal_entity.base_currency}）。蓝图 17.1.2.2 明确写着「本位币归法人，
 * 租户默认币种只是展示默认，两者不能混」—— 所以本模块**不**在 catalog 里另建一张本位币表，
 * 也不从任何请求头猜租户的默认币种。
 *
 * <p><b>为什么走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：法人档案在 identity 服务的库，
 * 汇率表在本服务（catalog）。跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，
 * 跨库 FK / 共享视图同理。所以本服务只通过 identity 的授权只读端点取数，
 * **本库里不存在任何法人 / 本位币的副本**。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，
 * identity 再向它自己的成员关系解析出租户并绑定 RLS 租户上下文。
 * 本端口**不**额外发明 {@code X-Tenant-Id} 之类的头传租户 ——
 * 那等于新建一条「客户端自称租户」的旁路，而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>🔴 读不到 ≠ 没有本位币</b>：取不到时抛 {@link FunctionalCurrencyUnavailableException}，
 * 由服务层翻译成 503。**绝不**返回空集合冒充「这个租户没有本位币」——
 * 那会让「各币种对各本位币的汇率」变成一个空面板，看不出是「没配法人」还是「identity 挂了」。
 */
public interface FunctionalCurrencyPort {

  /** 一个法人的本位币。 */
  record FunctionalCurrency(
      UUID legalEntityId, String code, String name, String baseCurrency, String status) {}

  /** 上游不可达 / 返回非 2xx / 返回体不认。由服务层翻译成 503。 */
  class FunctionalCurrencyUnavailableException extends RuntimeException {
    public FunctionalCurrencyUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public FunctionalCurrencyUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 取本租户全部法人的本位币。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 identity，租户由它从成员关系解析</b>）
   */
  List<FunctionalCurrency> list(String bearer);

  /** 真实的 HTTP 实现。 */
  class HttpFunctionalCurrencyPort implements FunctionalCurrencyPort {

    private final RestClient client;

    public HttpFunctionalCurrencyPort(String identityUrl) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(identityUrl).requestFactory(factory).build();
    }

    @Override
    public List<FunctionalCurrency> list(String bearer) {
      List<LegalEntityView> body;
      try {
        body =
            client
                .get()
                .uri("/identity/legal-entities")
                .header("Authorization", bearer)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<LegalEntityView>>() {});
      } catch (RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
          throw new FunctionalCurrencyUnavailableException(
              "身份服务拒绝了这次调用（"
                  + status
                  + "）：读取本位币需要 identity.legal_entity.read 权限，"
                  + "跨服务调用必须透传当前用户的 Authorization 头，租户由身份服务从已认证成员关系解析",
              e);
        }
        throw new FunctionalCurrencyUnavailableException(
            "身份服务返回 " + status + "，读不到本位币", e);
      } catch (RestClientException e) {
        throw new FunctionalCurrencyUnavailableException(
            "身份服务暂时不可用，读不到本位币（"
                + e.getClass().getSimpleName()
                + "）："
                + e.getMessage(),
            e);
      }
      if (body == null) {
        throw new FunctionalCurrencyUnavailableException("身份服务返回了空的法人列表，读不到本位币");
      }
      var out = new java.util.ArrayList<FunctionalCurrency>();
      for (var view : body) {
        if (view == null || view.baseCurrency() == null || view.baseCurrency().isBlank()) continue;
        out.add(
            new FunctionalCurrency(
                view.id(), view.code(), view.legalName(), view.baseCurrency(), view.status()));
      }
      return out;
    }
  }

  /**
   * 没配 identity 地址时的实现：**显式抛错**，绝不返回空集合。
   *
   * <p>理由与 {@link CostBasisPort.UnwiredCostBasisPort} 相同：空集合会让页面显示
   * 「没有本位币」，而真实情况是「我们没接 identity」—— 前者会被当成业务结论。
   */
  class UnwiredFunctionalCurrencyPort implements FunctionalCurrencyPort {
    @Override
    public List<FunctionalCurrency> list(String bearer) {
      throw new FunctionalCurrencyUnavailableException(
          "未配置身份服务地址（IDENTITY_URL），读不到本位币。本位币归法人（17.1.2.2，identity 服务），"
              + "本服务不另建本位币表 —— 读不到时显示「读不到」而不是显示「没有本位币」。");
    }
  }

  /**
   * identity 的法人视图（只取本模块用得到的字段）。
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：identity 将来给法人档案加字段时，
   * 本模块不应该因为「多了一个不认识��字段」就整条读不到。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record LegalEntityView(
      UUID id,
      String code,
      String legalName,
      String baseCurrency,
      String status) {}
}
