package com.starcon.channel.warehouse.platform;

import com.starcon.channel.warehouse.OverseasWarehouseAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * 10.4.2.2 备货预报的平台仓适配实现（PLATFORM_FBA）。
 *
 * <p>⚠️ 这**不是**一个打真实平台域名的 HTTP 客户端——本仓库没有真实的平台账号与网络出口，
 * 写一个指向假域名的客户端只会制造一个看起来能连、实际永远失败的假能力。
 *
 * <p>它把「与平台侧交互」这一跳收敛到 {@link OverseasWarehouseAdapter#submitForecast}：
 * 数据从 {@code warehouse_shipment_report}（租户/通道登记的**平台货件报送行**）读出，
 * 走完整真实的读取 → 受理回执 → 货件任务落库链路。
 *
 * <p>因此它满足「可真实驱动」：预报单真落库、货件任务真推进、平台货件编号真写库。
 * 将来接真实平台 API 时，只需新增一个 {@link OverseasWarehouseAdapter} 实现并注册，
 * 上层服务与数据模型一行都不用改。
 *
 * <p>🔴 平台货件编号**不是本服务凭空生成的**：它来自报送行里真实登记的那个编号
 * （来源可能是平台回调，也可能是人工登记）。报送行没有登记时抛
 * {@link WarehouseProviderException}，让货件任务进入 FAILED 并可重试 ——
 * 绝不返回编造的编号冒充「货件已创建」。
 *
 * <p>本实现同时提供 {@link #fetchStock}：平台仓账户的库存观测同样来自租户/通道登记的
 * 报送行（{@code warehouse_inbound_stock_line}，10.4.2.1 的输入表），与
 * {@code ReportedFeedWarehouseAdapter} 同一形状 —— 各 vendor 独立包、同一统一接口。
 */
@Component
public class PlatformShipmentWarehouseAdapter implements OverseasWarehouseAdapter {
  static final String PROVIDER_CODE = "PLATFORM_FBA";

  /** 受理来源标识：写进回执说明里，对账时能区分这批货件是从哪条通道登记的。 */
  static final String SOURCE = "PLATFORM_SHIPMENT_REPORT";

  private final DSLContext sql;

  public PlatformShipmentWarehouseAdapter(DSLContext sql) {
    this.sql = sql;
  }

  @Override
  public String providerCode() {
    return PROVIDER_CODE;
  }

  @Override
  public StockSnapshot fetchStock(ConnectionSettings settings) {
    // 与 10.4.2.1 的 ReportedFeedWarehouseAdapter 同款：一次拉取返回每个 SKU 的**当前值**，
    // 不是历史行。多余的历史行会被观测表唯一约束 (account, sku, observed_at) 互相吞掉，
    // 让「服务商改正过的值」被 ON CONFLICT DO NOTHING 静默丢弃。
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

    return new StockSnapshot(lines, latest == null ? Instant.EPOCH : latest.toInstant(), SOURCE);
  }

  @Override
  public ShipmentAck submitForecast(ConnectionSettings settings, ForecastPlan plan) {
    // 外部边界：租户/通道登记的**平台货件报送行**。它记的是「这批预报已经以某个
    // 平台货件编号推给了平台仓」，所以货件编号是**被登记进来的**，不是这里生成的。
    var reports =
        sql.fetch(
            "select external_shipment_ref,source,reported_at from warehouse_shipment_report"
                + " where account_id=? and forecast_id=? order by reported_at desc, id",
            settings.accountId(),
            plan.forecastId());

    if (reports.isEmpty()) {
      // 没有登记 = 平台侧还没有这次货件。**不**编一个编号冒充成功：
      // 任务会进入 FAILED（可重试），运营去登记/补登记后再重试。
      throw new WarehouseProviderException(
          "平台侧尚未登记本预报的货件编号（请先登记平台货件报送行后再推进货件任务）", false);
    }

    var row = reports.get(0);
    String shipmentRef = row.get("external_shipment_ref", String.class);
    if (shipmentRef == null || shipmentRef.isBlank()) {
      throw new WarehouseProviderException("平台货件报送行的货件编号为空", false);
    }

    // 受理时点取报送行自带的时点：观测/受理延迟必须可见，
    // 用本地时钟冒充会让「压了三天才登记」看起来是刚发生的。
    OffsetDateTime reportedAt = row.get("reported_at", OffsetDateTime.class);
    Instant acknowledgedAt = reportedAt == null ? Instant.EPOCH : reportedAt.toInstant();

    return new ShipmentAck(
        shipmentRef,
        "平台货件 " + shipmentRef + " 已登记受理（来源 " + row.get("source", String.class) + "）",
        acknowledgedAt);
  }
}
