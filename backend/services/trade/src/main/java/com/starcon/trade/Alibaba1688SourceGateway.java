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
 * 6.1.2.1 **1688 开放平台**采集实现（默认<b>未接线</b>）。
 *
 * <p>蓝图职责句点名「管理 1688/义乌购等货源渠道的店铺档口，抓取与同步在售货源」，所以这个 vendor 的适配器
 * <b>真实存在</b>、报文结构也按 1688 开放平台的「店铺商品列表」形状写；但它<b>默认未接线</b>，原因是仓库里没有它的接入信息：
 *
 * <ul>
 *   <li>没有登记 base_url（不同部署 / 不同区域的开放平台网关地址不同）；</li>
 *   <li>没有凭证引用 —— 1688 开放平台要 appKey/appSecret + 商家授权 accessToken，
 *       这些必须来自 secret manager（AGENTS.md 安全条：任何 secret 不入仓库）；</li>
 *   <li>没有做过一次真实联调，因此**不能声称它可用**。
 * </ul>
 *
 * <p>因此未登记时 {@link #collect} 抛 {@link SupplierSourceGateway.SourceUnavailableException}，
 * 服务层据此把这次采集置为 FAILED 并落一条失败原因。 它<b>绝不</b>在未接线时返回一批编造的货源 ——
 * 那会让界面上出现一批**看起来是真的**货源（标题、价格、起订量俱全），而 1688 那边根本没有这些货；
 * 这是本模块最危险的一种假数据，因为它恰好落在蓝图最在意的那条链上（比价 → 采购下单带入货源价）。
 *
 * <p>接上之后要改的只有 {@link #collect} 里的报文与鉴权， 服务层与统一接口一个字都不用动 ——
 * 这正是「每个 vendor 独立包」的意义。
 */
@Service
public class Alibaba1688SourceGateway extends HttpSupplierSourceGateway {

  public Alibaba1688SourceGateway(DSLContext sql, ObjectMapper json) {
    super(sql, json);
  }

  @Override
  public String platform() {
    return "ALIBABA_1688";
  }

  @Override
  public String displayName() {
    return "1688 开放平台";
  }

  @Override
  protected String collectPath() {
    return "/open/shop/items";
  }

  @Override
  protected String defaultCurrency() {
    // 1688 的报价以人民币计价 —— 这是**平台的事实**，不是猜的默认值。
    return "CNY";
  }

  @Override
  public String wiringRequirement() {
    return "尚未登记 1688 开放平台的采集网关地址（base_url）与凭证引用（credential_ref）";
  }

  @Override
  public CollectedBatch collect(StallRequest request) {
    var endpoint = endpoint();
    if (endpoint == null) {
      // 纪律：不伪造。显式失败 → 服务层落 FAILED 批次 + 失败原因 → 运营看得见。
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
      String currency = raw.currency() == null || raw.currency().isBlank()
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

  /** 1688「店铺商品列表」的响应形状。价格与起订量一律**字符串**，避免 JSON 数字进 double。 */
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
