package com.starcon.channel.warehouse.reported;

import com.starcon.channel.warehouse.OverseasWarehouseAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * 10.4.2.1 的默认服务商适配实现（GENERIC_3PL）。
 *
 * <p>⚠️ 这**不是**一个打真实域名的 HTTP 客户端——本仓库没有真实的海外仓服务商账号与网络出口， 写一个指向假域名的客户端只会制造一个看起来能连、实际永远失败的假能力。
 *
 * <p>它做的是把**外部调用的边界**收敛到这个接口：数据从 {@code warehouse_inbound_stock_line}（租户/通道已落库的报送行）读出， 走完整真实的读取 →
 * 观测快照落库 → 同步记录落库链路。
 *
 * <p>因此它满足「可真实驱动」：输入真落库、同步真执行、观测真写库， 换成真实服务商时只需另写一个 {@link OverseasWarehouseAdapter} 实现并注册，
 * 上层服务与数据模型一行都不用改。
 *
 * <p>source 常量标记观测来源，保证每条观测都能回答「这个数从哪来」—— 外部库存要与自营库存对账，追不回来的数字对不了账。
 */
@Component
public class ReportedFeedWarehouseAdapter implements OverseasWarehouseAdapter {
  static final String PROVIDER_CODE = "GENERIC_3PL";

  /** 观测来源标识：写死在观测行上，对账时能区分这批数来自哪条通道。 */
  static final String SOURCE = "REPORTED_FEED";

  private final DSLContext sql;

  public ReportedFeedWarehouseAdapter(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public String providerCode() {
    return PROVIDER_CODE;
  }

  @Override
  public StockSnapshot fetchStock(ConnectionSettings settings) {
    // 一次拉取返回的是**每个 SKU 的当前值**，不是它的历史行。
    // 必须去重到最新一条：如果把同一 SKU 的多条报送都返回，它们会被盖上同一个观测时点，
    // 然后在观测表的唯一约束 (account, sku, observed_at) 上互相吞掉——
    // 服务商改正过的那个数会被 ON CONFLICT DO NOTHING 静默丢弃，观测值看起来正常其实是旧的。
    List<StockLine> lines =
        sql
            .fetch(
                "select distinct on (external_sku) external_sku,quantity,unit,reported_at"
                    + " from warehouse_inbound_stock_line where account_id=?"
                    + " order by external_sku,reported_at desc",
                settings.accountId())
            .stream()
            .map(
                r ->
                    new StockLine(
                        r.get("external_sku", String.class),
                        r.get("quantity", BigDecimal.class),
                        r.get("unit", String.class)))
            .toList();

    // 观测时点取该账户报送行的最新时点，而不是本地时钟：
    // 观测延迟必须可见，用本地时钟冒充会让陈旧数据看起来是新鲜的。
    OffsetDateTime latest =
        sql.fetchOne(
                "select max(reported_at) from warehouse_inbound_stock_line where account_id=?",
                settings.accountId())
            .get(0, OffsetDateTime.class);

    // 没有报送行就是「服务商还没有数据」，不是故障：
    // 返回空快照，让同步记录如实记下 0 条，而不是报一次假错。
    return new StockSnapshot(lines, latest == null ? Instant.EPOCH : latest.toInstant(), SOURCE);
  }
}
