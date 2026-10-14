package com.starcon.trade;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 供应商下单 · <b>人工录入</b>实现（已接线）。
 *
 * <p>蓝图输入句明写两条路径：「供应商代发接口<b>或</b>人工录入」。本类实现后者： 运营在页面上把供应商单号、实际采购价与实际运费录进来，服务层据此回填并算差价。
 *
 * <p><b>刻意保留的一条纪律</b>：本 vendor <b>永远不会</b>自己「下单」。 {@link #place} 恒抛 {@link
 * SupplierOrderNotRecorded}，因为 「人工录入」这个 vendor 名下唯一的写动作是运营在界面上提交回执； 如果服务层在后台悄悄替人下一个单，那就是在伪造一次外部交易。
 *
 * <p>服务层走人工路径时**不调 {@code place}**，而是直接走「回填回执」端点， 两者都在同一个事务里落同样的告警。本类的存在意义是： 能力声明里如实报出「哪些 vendor
 * 已接线」时，人工录入是已接线的那一个。
 */
@Service
public class ManualEntrySupplierAdapter implements SupplierOrderGateway {

  /** 人工录入下单不存在后台调用；抛这个异常只为让误调用显式失败。 */
  static class SupplierOrderNotRecorded extends SupplierOrderException {
    SupplierOrderNotRecorded() {
      super(
          HttpStatus.BAD_REQUEST,
          "「人工录入」这条路径没有后台下单动作：供应商单号与实际成本由运营在页面上" + "提交回执，服务层只负责回填、算差价并落告警。");
    }
  }

  @Override
  public String vendorCode() {
    return "MANUAL_ENTRY";
  }

  @Override
  public String displayName() {
    return "人工录入（已接线）";
  }

  @Override
  public boolean wired() {
    // 人工录入**不需要**任何外部凭证：运营自己就是通路，所以它是已接线的那一个。
    return true;
  }

  @Override
  public String wiringRequirement() {
    return "";
  }

  @Override
  public SupplierOrderException unavailable(String reason) {
    return new SupplierOrderException(reason);
  }

  @Override
  public SupplierOrderAck place(PlacementRequest request) {
    throw new SupplierOrderNotRecorded();
  }
}
