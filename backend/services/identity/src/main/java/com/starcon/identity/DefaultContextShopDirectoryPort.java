package com.starcon.identity;

import static com.starcon.identity.DefaultContextModels.DefaultContextShopOption;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 1.4.2.2 默认店铺与仓库 —— <b>店铺目录</b>的跨服务只读端口（读 02 渠道与店铺）。
 *
 * <p><b>为什么必须走 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：
 * 店铺（{@code shop}）在 channel 服务的库里，默认上下文在 identity 服务。
 * 跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上，跨库 FK / 共享视图同理，
 * 而且一旦两边都往这张表写，就出现了两个写入所有者。
 * 所以本服务<b>只</b>通过 channel 的授权只读端点 {@code GET /channel/shops} 取数，
 * <b>identity 库里不存在任何店铺副本</b>。
 *
 * <p><b>这条端点带数据范围闸</b>：channel 的 {@code shop} 表上有 RESTRICTIVE
 * {@code data_scope} 策略（{@code channel_scope(...)}），按 {@code app.actor} /
 * {@code app.department} / {@code app.grants} 过滤 —— 即「你负责的店铺」。
 * 所以本端口拿到的列表<b>就是</b>「用户有权限的店铺集合」（蓝图 1.4.2.2 的输入），
 * 本服务**不再**自己算一遍范围（那才是第二套权限真相源）。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，
 * channel 再向 identity 确认已认证成员关系、解析出租户。本端口<b>不</b>额外发明
 * {@code X-Tenant-Id} 之类的头传租户：那等于新建一条「客户端自称租户」的旁路，
 * 而那正是 AGENTS.md 明令禁止的。
 *
 * <p><b>上游不可达 ≠ 上游没有店铺</b>：取不到时抛 {@link Unavailable}，
 * 由服务层翻译成「该维度读不到」并如实报出原因。
 * <b>绝不</b>返回空店铺列表冒充「这个租户没有店铺」——
 * 那会让「默认店铺」选择器变成空的，而用户看不出是上游挂了。
 */
public interface DefaultContextShopDirectoryPort {

  /** 店铺清单（channel 授权只读端点的原始行，只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShopRow(UUID id, String code, String name, String platformName, String status) {}

  /**
   * 取当前用户可见的店铺清单。
   *
   * @param bearer 调用者的 Authorization 头（<b>透传给 channel</b>）
   * @throws Unavailable 上游不可达 / 被拒绝 / 返回体不认
   */
  List<DefaultContextShopOption> shops(String bearer);

  /** 上游读不到。服务层把它翻成「该维度读不到」，而不是「没有店铺」。 */
  class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }

    public Unavailable(String message) {
      super(message);
    }
  }

  /** 真实的 HTTP 实现。 */
  class HttpDefaultContextShopDirectoryPort implements DefaultContextShopDirectoryPort {

    /** channel 的店铺清单出口（2.3.1.1 店铺列表）。 */
    static final String SHOPS_PATH = "/channel/shops";

    private final RestClient client;

    public HttpDefaultContextShopDirectoryPort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public List<DefaultContextShopOption> shops(String bearer) {
      ShopRow[] rows;
      try {
        rows =
            client
                .get()
                .uri(SHOPS_PATH)
                .header("Authorization", bearer)
                .retrieve()
                .body(ShopRow[].class);
      } catch (RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401 || code == 403) {
          throw new Unavailable(
              "当前角色没有店铺读取权限（channel 拒绝：" + code + "）：店铺清单读不到",
              e);
        }
        throw new Unavailable("channel 服务返回 " + code + "：店铺清单读不到", e);
      } catch (RestClientException e) {
        throw new Unavailable("channel 服务不可达：店铺清单读不到", e);
      }
      if (rows == null) {
        throw new Unavailable("channel 服务没有返回店铺清单");
      }
      return java.util.Arrays.stream(rows)
          .filter(row -> row.id() != null)
          .map(
              row ->
                  new DefaultContextShopOption(
                      row.id(),
                      row.code(),
                      row.name(),
                      row.platformName(),
                      row.status()))
          .toList();
    }
  }

  /**
   * 「上游没配」的实现。
   *
   * <p><b>刻意不是「返回空列表」</b>：空列表与「这个租户真的没有店铺」在界面上长得一模一样，
   * 用户会以为系统坏了。地址没配就必须说「没配」。
   */
  class UnwiredDefaultContextShopDirectoryPort implements DefaultContextShopDirectoryPort {

    @Override
    public List<DefaultContextShopOption> shops(String bearer) {
      throw new Unavailable(
          HttpStatus.SERVICE_UNAVAILABLE.value()
              + "：渠道服务地址未配置（CHANNEL_URL 为空）：店铺清单的唯一来源是 channel 的"
              + "授权只读端点 GET /channel/shops（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
              + "本服务不会凭空报一个店铺清单。请配置 CHANNEL_URL 后重试");
    }
  }
}
