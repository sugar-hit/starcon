package com.starcon.channel;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * 金额值对象（金额 + 币种）。
 *
 * <p>🔴 AGENTS.md 要求「金额与业务 ID 用值对象表达」且「禁止浮点记账：金额一律 {@code BigDecimal}
 * + 数据库 {@code NUMERIC}」。本 record 是那层值对象：金额永远**带着币种**存在，
 * 没有「一个没有币种的数字」。
 *
 * <p>🔴🔴 <b>不同币种不得直接相加</b> —— {@link #plus(Money)} 在币种不一致时**直接抛异常**，
 * 而不是给出一个看起来合理的和。这是本仓最容易出的一种静默错误：把 100 USD 和 100 EUR
 * 加成「200」会被读成「200 美元」，而这个数字从头到尾没有任何依据。
 * <b>要跨币种合计，必须先有明确的折算口径</b> —— 本模块不假设任何隐含汇率
 * （假设一个隐含汇率比不合计更危险），所以违规台账的扣款合计**按币种分组**返回。
 *
 * <p>🔴 金额精度：最多 6 位小数（与 DB 的 {@code numeric(19,6)} 同一口径）。
 * 超出即拒 —— 静默四舍五入会把「平台罚了 10.0000001」变成「罚了 10」，那是改事实。
 */
public record Money(BigDecimal amount, String currency) {

  /** 金额最多的小数位数（与 {@code numeric(19,6)} 对齐）。 */
  public static final int SCALE = 6;

  public Money {
    Objects.requireNonNull(amount, "金额不能为空");
    Objects.requireNonNull(currency, "币种不能为空");
    String normalized = currency.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("币种必须是三位大写字母：" + currency);
    }
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("金额不能为负：" + amount.toPlainString());
    }
    if (amount.stripTrailingZeros().scale() > SCALE) {
      throw new IllegalArgumentException("金额最多 " + SCALE + " 位小数：" + amount.toPlainString());
    }
    currency = normalized;
  }

  /** 某个币种的零。 */
  public static Money zero(String currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  /** 🔴 同币种相加；币种不同直接拒（不折算、不假设汇率）。 */
  public Money plus(Money other) {
    Objects.requireNonNull(other, "另一个金额不能为空");
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "不同币种不得直接相加：" + currency + " 与 " + other.currency
              + "（要合计必须先有明确的折算口径）");
    }
    return new Money(amount.add(other.amount), currency);
  }

  /** 展示串（金额 + 币种）。 */
  public String display() {
    return amount.stripTrailingZeros().toPlainString() + " " + currency;
  }
}
