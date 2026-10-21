package com.starcon.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 6.1.2.1 **义乌购**采集实现（默认<b>未接线</b>）。
 *
 * <p>蓝图职责句点名「1688/义乌购等货源渠道」，所以义乌购是本模块的**第二个 vendor**：它有自己的接入登记
 * （{@code supplier_source_collector_endpoint} 里按 platform 分行）、自己的报文形状，
 * 但**暴露的是同一个 {@link SupplierSourceGateway} 接口** —— 服务层不认任何 vendor 的报文。
 *
 * <p>纪律与 {@link Alibaba1688SourceGateway} 逐字同构：
 *
 * <ul>
 *   <li>默认未接线（仓库里没有它的网关地址与凭证引用）；</li>
 *   <li>未接线时<b>显式失败</b>并说明缺什么，绝不返回一批编造的货源；</li>
 *   <li>坏数据（价格区间写反 / 起订量非法 / 币种非法）**跳过并写明原因**，不静默丢弃；</li>
 *   <li>凭证只存 secret manager 的**引用**，本仓不持有任何明文。</li>
 * </ul>
 *
 * <p>义乌购档口也可以走「插件采集」路径（{@code PLUGIN_PUSH}）：插件把采到的货源条目推上来，
 * 那条路径<b>不需要</b>任何外部凭证 —— 这也是本模块在未接线状态下仍然能真实运转的方式。
 */
@Service
public class YiwuGouSourceGateway extends HttpSupplierSourceGateway {

  public YiwuGouSourceGateway(DSLContext sql, ObjectMapper json) {
    super(sql, json);
  }

  @Override
  public String platform() {
    return "YIWU_GOU";
  }

  @Override
  public String displayName() {
    return "义乌购";
  }

  @Override
  protected String collectPath() {
    return "/open/shop/items";
  }

  @Override
  protected String defaultCurrency() {
    // 义乌购的报价以人民币计价 —— 平台事实。
    return "CNY";
  }

  @Override
  public String wiringRequirement() {
    return "尚未登记义乌购的采集网关地址（base_url）与凭证引用（credential_ref）";
  }

  @Override
  public CollectedBatch collect(StallRequest request) {
    var endpoint = endpoint();
    if (endpoint == null) {
      throw unwired();
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("shopId", request.platformStallId());
    payload.put("pageSize", request.maxItems());
    String body = postCollect(endpoint, payload);
    ShopItemsBody parsed = parse(body, ShopItemsBody.class);
    if (parsed == null || parsed.items() == null) {
      throw new SupplierSourceGateway.SourceUnavailableException(
          displayName() + " 返回的报文里没有商品列表，这次没有采到任何货源");
    }
    List<CollectedItem> items = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (ShopItem raw : parsed.items()) {
      if (raw == null || raw.itemId() == null || raw.itemId().isBlank()) {
        skipped.add("有一条货源没有平台货源 ID，无法入库，已跳过");
        continue;
      }
      BigDecimal[] range = priceRange(raw.priceMin(), raw.priceMax());
      if (range == null) {
        skipped.add(
            "货源 "
                + raw.itemId()
                + " 的价格区间不可用（下限「"
                + raw.priceMin()
                + "」上限「"
                + raw.priceMax()
                + "」），已跳过");
        continue;
      }
      BigDecimal moq = quantity(raw.moq());
      if (moq == null) {
        skipped.add("货源 " + raw.itemId() + " 的起订量不可用（「" + raw.moq() + "」），已跳过");
        continue;
      }
      String currency =
          raw.currency() == null || raw.currency().isBlank()
              ? defaultCurrency()
              : raw.currency().trim().toUpperCase(java.util.Locale.ROOT);
      if (!currency.matches("^[A-Z]{3}$")) {
        skipped.add("货源 " + raw.itemId() + " 的币种「" + raw.currency() + "」不是三位大写，已跳过");
        continue;
      }
      items.add(
          new CollectedItem(
              raw.itemId().trim(),
              raw.title() == null || raw.title().isBlank() ? raw.itemId().trim() : raw.title().trim(),
              range[0],
              range[1],
              currency,
              moq,
              raw.spec() == null ? "" : raw.spec(),
              raw.imageUrl() == null ? "" : raw.imageUrl(),
              raw.itemUrl() == null ? "" : raw.itemUrl(),
              raw.onSale() == null || raw.onSale()));
    }
    return batch(items, skipped);
  }

  /** 义乌购「店铺商品列表」的响应形状。价格与起订量一律**字符串**，避免 JSON 数字进 double。 */
  record ShopItemsBody(List<ShopItem> items) {}

  record ShopItem(
      String itemId,
      String title,
      String priceMin,
      String priceMax,
      String currency,
      String moq,
      String spec,
      String imageUrl,
      String itemUrl,
      Boolean onSale) {}
}
