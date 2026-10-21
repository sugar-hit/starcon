package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * 6.2.1.2 补货建议读上游 3.6.2 报价与 3.1.2.4 单位换算的<b>跨服务端口</b>。
 *
 * <p>蓝图本模块的输入句写死「MOQ 与箱规（3.6.2）」，而 3.6.2 报价与 3.1.2.4 单位换算
 * 都在 <b>catalog 服务的库</b>里。AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，
 * 所以本端口<b>只经 catalog 的授权只读端点</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 {@link StockoutInventoryPort}（读 warehouse）、{@link SupplierPartyQueryPort}（读 catalog）
 * 逐字同构 —— 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>。
 *
 * <p>用两个已交付形状：
 *
 * <ul>
 *   <li>{@code GET /catalog/supplier-quotes/effective?skuId=...&at=...} —— 3.6.2.1 的
 *       <b>有效报价列表</b>（蓝图输出句点名的那一条读出口）。<b>刻意不传 supplierId</b>：
 *       传了之后 catalog 只在<b>那一个供应商内部</b>比优先级并返回 {@code selected}，
 *       而「建议供应商」正是本模块要给的答案 —— 供应商还没选出来之前不能先问 catalog
 *       「用哪条报价」。不传 supplierId 时 catalog 返回<b>全部有效报价候选</b>
 *       （{@code candidates}）且 {@code selected} 恒为 null，理由见 catalog 自己的注释：
 *       「优先级只在同一个供应商内部才有可比性」。</li>
 *   <li>{@code GET /catalog/skus/{id}} —— 2.x 的 SKU 详情。取两样东西：
 *       <b>分类</b>（用于「按分类配置覆盖天数」这条作用域）与<b>单位换算</b>
 *       （{@code unitConversions} 里 role='PURCHASE' 的 {@code quantityPerBase}
 *       就是<b>箱规</b>：1 个采购单位含多少基础单位）。</li>
 * </ul>
 *
 * <p><b>为什么箱规不能猜成 1</b>：箱规猜成 1 会把订量算少一箱（1 箱 = 12 件时差 12 倍），
 * 而且页面上看不出任何异常。所以箱规量不到时是 <b>null</b>（=「量不到」），由服务层如实标出
 * 「未做箱规取整」，而不是悄悄按 1 件算。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （trade 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>取不到就<b>显式失败</b>（503），绝不返回空集合冒充「这个 SKU 没有报价」—— 那会让建议
 * 全部退化成 NO_QUOTE，而真实情况是商品主数据服务挂了。
 */
@Service
public class ReplenishmentCatalogPort {

  /** catalog 侧的 SKU 详情（只声明本模块真正用到的字段）。 */
  record SkuView(UUID id, String code, String name, String category, String baseUnit, List<ConversionView> unitConversions) {}

  /** 单位换算的一行（1 个该单位 = 多少个基础单位）。 */
  record ConversionView(String unitCode, String role, BigDecimal quantityPerBase, boolean current) {}

  /** 一条有效报价（只声明本模块真正用到的字段）。 */
  record QuoteView(
      UUID id,
      UUID supplierRoleId,
      UUID supplierId,
      String supplierName,
      String purchaseUnitCode,
      String currency,
      BigDecimal moqQuantity,
      int leadTimeDays,
      int priority,
      List<TierView> tiers) {}

  /** 阶梯价的一档（开口阶梯：取 minQuantity &le; 数量 里最大的一档）。 */
  record TierView(BigDecimal minQuantity, BigDecimal unitPrice) {}

  /** 有效报价解析结果。 */
  record QuoteResult(List<QuoteView> candidates, boolean ambiguous, String ambiguityProblem, String note) {}

  // ================================================================
  // 6.3.1.2 采购单创建与编辑新增的形状（**纯增量**：不动上面两个既有 record，
  // 以免 6.2.1.2 断货建议的既有调用点与测试编译不过）。
  // ================================================================

  /** 一条有效报价（带出报价用；比 {@link QuoteView} 多有效期与选择状态）。 */
  record EffectiveQuoteView(
      UUID id,
      UUID supplierId,
      String supplierName,
      String skuCode,
      String skuName,
      String currency,
      String purchaseUnitCode,
      BigDecimal moqQuantity,
      int leadTimeDays,
      int priority,
      LocalDate validFrom,
      LocalDate validTo,
      List<EffectiveTierView> tiers,
      String selectionState) {}

  /** 一档阶梯价。 */
  record EffectiveTierView(BigDecimal minQuantity, BigDecimal unitPrice) {}

  /**
   * catalog 的有效报价解析结果（逐字对齐 {@code SupplierQuoteEffectiveResult}）。
   *
   * @param selected 唯一可自动采用的那条；**歧义时为 null**（规则 R2：不替用户挑）
   * @param selectedTier 命中的阶梯（数量落在哪一档就取哪一档；低于最低档为 null）
   * @param candidates 全部有效报价候选（并列时逐条列出来，供人工选）
   */
  record EffectiveResult(
      UUID skuId,
      String skuCode,
      String skuName,
      UUID supplierId,
      String supplierName,
      LocalDate at,
      EffectiveQuoteView selected,
      EffectiveTierView selectedTier,
      List<EffectiveQuoteView> candidates,
      boolean ambiguous,
      String ambiguityProblem,
      List<String> warnings,
      String note) {}


  /** 端口专用的 ObjectMapper：**忽略上游多出来的字段**。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public ReplenishmentCatalogPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：一次计算要逐个 SKU 翻目录，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时商品主数据一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  /**
   * 读一个 SKU 的详情（分类 + 单位换算 = 箱规）。
   *
   * <p>不存在就 404，<b>不返回 null 让调用方自己判空</b>：「商品不存在」与「商品服务挂了」
   * 是两件事，前者该当场拒绝，后者该重试。
   */
  public SkuView sku(UUID skuId, String bearer) {
    SkuView view;
    try {
      view =
          client
              .get()
              .uri("/catalog/skus/" + skuId)
              .header("Authorization", bearer)
              .retrieve()
              .body(SkuView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "商品不存在：SKU " + skuId + "，补货建议算不出这个 SKU 的箱规");
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取商品主数据，补货建议暂时算不出箱规与分类");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (view == null || view.id() == null) {
      throw unavailable("商品主数据服务没有返回这个 SKU");
    }
    return view;
  }

  /**
   * 读一个 SKU 的<b>全部有效报价候选</b>（3.6.2.1 的有效报价列表）。
   *
   * <p>刻意不传 {@code supplierId}：见类注释 —— 供应商还没选出来之前不能先让 catalog
   * 在某一个供应商内部选一条。
   *
   * <p><b>歧义（同一优先级多条有效报价）不替用户挑</b>：catalog 会把并列的候选全部列出来并
   * 给出 {@code ambiguityProblem}，本模块照原文带回来。
   */
  public QuoteResult quotes(UUID skuId, LocalDate at, String bearer) {
    QuotePageView page;
    try {
      page =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path("/catalog/supplier-quotes/effective").queryParam("skuId", skuId);
                    if (at != null) {
                      b.queryParam("at", at.toString());
                    }
                    return b.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(QuotePageView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取供应商报价，补货建议暂时取不到起订量与交期");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (page == null || page.candidates() == null) {
      throw unavailable("商品主数据服务没有返回有效报价列表");
    }
    List<QuoteView> candidates = new ArrayList<>();
    for (QuoteViewView candidate : page.candidates()) {
      if (candidate == null || candidate.supplierRoleId() == null) {
        continue;
      }
      List<TierView> tiers = new ArrayList<>();
      if (candidate.tiers() != null) {
        for (TierViewView tier : candidate.tiers()) {
          if (tier == null || tier.minQuantity() == null) {
            continue;
          }
          tiers.add(new TierView(tier.minQuantity(), tier.unitPrice()));
        }
      }
      candidates.add(
          new QuoteView(
              candidate.id(),
              candidate.supplierRoleId(),
              candidate.supplierId(),
              candidate.supplierName() == null ? "" : candidate.supplierName(),
              candidate.purchaseUnitCode(),
              candidate.currency(),
              candidate.moqQuantity(),
              candidate.leadTimeDays(),
              candidate.priority(),
              tiers));
    }
    return new QuoteResult(
        candidates, Boolean.TRUE.equals(page.ambiguous()), page.ambiguityProblem(), page.note());
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：MOQ / 箱规 / 交期 / 价格的事实来源是商品主数据服务的授权只读接口"
            + "（3.6.2.1 报价与 3.1.2.4 单位换算，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份报价或箱规出来算建议量");
  }

  // ================================================================
  // 6.3.1.2 采购单创建与编辑：带出报价（严格取价 / 非严格预览）
  // ================================================================

  /**
   * 带出报价（<b>不严格</b>）：把该 SKU 在该供应商下的<b>全部</b>有效报价候选列出来，
   * 歧义时如实标 {@code ambiguous=true} 并把并列的候选都留着，<b>不替用户挑</b>。
   *
   * <p>用于**建单表单的预览**：用户要看到「有哪几条价、为什么不能自动选」。
   *
   * @param supplierId 必填：**一张采购单只对一个供应商**，优先级只在供应商内部可比
   */
  public EffectiveResult effective(
      UUID skuId, UUID supplierId, LocalDate at, BigDecimal quantity, String bearer) {
    return fetchEffective("/catalog/supplier-quotes/effective", skuId, supplierId, at, quantity, bearer);
  }

  /**
   * 严格取价（<b>歧义时 409</b>）。
   *
   * <p>用于**提交那一刻**：采购单不能带着一条「系统随便选的价」发出去，
   * 而一个 200 + selected=null 很容易被前端误读成「没报价」——
   * 症状是提示文案答非所问，真正的原因却是优先级并列。
   * ⇒ 歧义就报错让用户去改优先级。
   *
   * <p>409 与 404 都**如实透出**：
   * 409 = 有并列报价要人选；404 = 这个 SKU 该供应商现在没价可下（这是一个需要解决的阻断）。
   */
  public EffectiveResult resolve(
      UUID skuId, UUID supplierId, LocalDate at, BigDecimal quantity, String bearer) {
    return fetchEffective(
        "/catalog/supplier-quotes/effective/resolve", skuId, supplierId, at, quantity, bearer);
  }

  private EffectiveResult fetchEffective(
      String path,
      UUID skuId,
      UUID supplierId,
      LocalDate at,
      BigDecimal quantity,
      String bearer) {
    EffectiveResultView view;
    try {
      view =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(path).queryParam("skuId", skuId);
                    if (supplierId != null) {
                      b.queryParam("supplierId", supplierId);
                    }
                    if (at != null) {
                      b.queryParam("at", at.toString());
                    }
                    if (quantity != null) {
                      b.queryParam("quantity", quantity.toPlainString());
                    }
                    return b.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(EffectiveResultView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取供应商报价，采购单带不出价格");
      }
      if (e.getStatusCode() == HttpStatus.CONFLICT) {
        // catalog 的歧义 409：**不吞掉** —— 「报错让用户选」必须发生在建单的那一刻。
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "供应商报价有歧义：同一优先级存在多条有效报价，请先在报价台账里定下优先级");
      }
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "该 SKU 在该供应商下现在没有有效报价，无法带出价格");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (view == null || view.candidates() == null) {
      throw unavailable("商品主数据服务没有返回有效报价列表");
    }
    return view.toResult();
  }

  // ---- catalog 侧的有效报价响应形状（只声明用到的字段） ----

  record EffectiveResultView(
      UUID skuId,
      String skuCode,
      String skuName,
      UUID supplierId,
      String supplierName,
      LocalDate at,
      EffectiveQuoteViewRaw selected,
      EffectiveTierViewRaw selectedTier,
      List<EffectiveQuoteViewRaw> candidates,
      boolean ambiguous,
      String ambiguityProblem,
      List<String> warnings,
      String note) {

    EffectiveResult toResult() {
      List<EffectiveQuoteView> list = new ArrayList<>();
      if (candidates != null) {
        for (EffectiveQuoteViewRaw raw : candidates) {
          if (raw == null) {
            continue;
          }
          list.add(raw.toView());
        }
      }
      return new EffectiveResult(
          skuId,
          skuCode,
          skuName,
          supplierId,
          supplierName,
          at,
          selected == null ? null : selected.toView(),
          selectedTier == null
              ? null
              : new EffectiveTierView(selectedTier.minQuantity(), selectedTier.unitPrice()),
          list,
          ambiguous,
          ambiguityProblem,
          warnings == null ? List.of() : warnings,
          note);
    }
  }

  record EffectiveQuoteViewRaw(
      UUID id,
      UUID supplierId,
      String supplierName,
      String skuCode,
      String skuName,
      String currency,
      String purchaseUnitCode,
      BigDecimal moqQuantity,
      int leadTimeDays,
      int priority,
      LocalDate validFrom,
      LocalDate validTo,
      List<EffectiveTierViewRaw> tiers,
      String selectionState) {

    EffectiveQuoteView toView() {
      List<EffectiveTierView> tierList = new ArrayList<>();
      if (tiers != null) {
        for (EffectiveTierViewRaw tier : tiers) {
          if (tier == null || tier.minQuantity() == null) {
            continue;
          }
          tierList.add(new EffectiveTierView(tier.minQuantity(), tier.unitPrice()));
        }
      }
      return new EffectiveQuoteView(
          id,
          supplierId,
          supplierName == null ? "" : supplierName,
          skuCode == null ? "" : skuCode,
          skuName == null ? "" : skuName,
          currency,
          purchaseUnitCode,
          moqQuantity,
          leadTimeDays,
          priority,
          validFrom,
          validTo,
          tierList,
          selectionState);
    }
  }

  record EffectiveTierViewRaw(BigDecimal minQuantity, BigDecimal unitPrice) {}


  // ---- catalog 侧的响应形状（只声明本模块用到的字段） ----

  record QuotePageView(
      List<QuoteViewView> candidates, boolean ambiguous, String ambiguityProblem, String note) {}

  record QuoteViewView(
      UUID id,
      UUID supplierRoleId,
      UUID supplierId,
      String supplierName,
      String purchaseUnitCode,
      String currency,
      BigDecimal moqQuantity,
      int leadTimeDays,
      int priority,
      List<TierViewView> tiers) {}

  record TierViewView(BigDecimal minQuantity, BigDecimal unitPrice) {}
}
