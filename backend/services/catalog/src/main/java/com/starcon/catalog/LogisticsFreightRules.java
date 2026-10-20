package com.starcon.catalog;

import static com.starcon.catalog.LogisticsFreightModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 5.2.3.2 物流与头程费用：<b>纯算术与校验</b>（不碰数据库、不碰 HTTP）。
 *
 * <p>它单独成一个类有两个理由：
 *
 * <ol>
 *   <li><b>算式必须能被单独证明</b>。「运费占成本比」要用的是**一条恒等式**
 *       （基础运费 = 首段 + 续段，附加费 = 基础运费 × 费率），
 *       它必须有一个不依赖数据库的单元测试钉住 —— 集成测试只能证明「落库了」，
 *       证明不了「算对了」。
 *   <li><b>测算与差异对照共用同一个算式</b>。测算（事前）与差异对照（事后）
 *       用的是**同一个** {@link #baseFreight} / {@link #applySurcharge}：
 *       差异 = 实际 − 按规则测算。两边各写一套算法的话，
 *       差异里的「按规则测算」与测算页的「测算」就会分叉，
 *       而差异恰恰是用来解释测算为什么不准的 —— 两套算法会让它解释不了任何东西。
 * </ol>
 *
 * <p><b>🔴 全程 BigDecimal，禁浮点</b>（AGENTS.md：费率 / 重量 / 体积也必须是精确值）。
 * 费率的单位是<b>小数</b>（{@code 0.15} = 15%）。
 */
public final class LogisticsFreightRules {

  private LogisticsFreightRules() {}

  /** 金额精度：与本仓其它金额列一致（4 位小数）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量精度：与本仓其它数量列一致（6 位小数）。 */
  public static final int QUANTITY_SCALE = 6;

  /**
   * 比例费率的取值域上限。
   *
   * <p>费率是<b>小数</b>口径，所以上限是 1（= 100%）。把 15（当成 15% 的百分数）
   * 填进来的错误会在这里被当场拒掉，而不是变成 1500% 的附加费。
   */
  public static final BigDecimal MAX_RATE = BigDecimal.ONE;

  /** 体积换算系数：1 m³ = 1,000,000 cm³。 */
  private static final BigDecimal CM3_PER_M3 = new BigDecimal("1000000");

  // ================================================================ 词表

  /** 计费口径 → 中文名。未知取值原样返回（不假装认识）。 */
  public static String chargeBasisLabel(String value) {
    if (value == null) return null;
    for (ChargeBasis basis : ChargeBasis.values()) {
      if (basis.name().equals(value)) return basis.label();
    }
    return value;
  }

  /** 计费口径 → 口径说明。 */
  public static String chargeBasisBasis(String value) {
    if (value == null) return null;
    for (ChargeBasis basis : ChargeBasis.values()) {
      if (basis.name().equals(value)) return basis.basis();
    }
    return null;
  }

  /** 附加费类型 → 中文名。 */
  public static String surchargeTypeLabel(String value) {
    if (value == null) return null;
    for (SurchargeType type : SurchargeType.values()) {
      if (type.name().equals(value)) return type.label();
    }
    return value;
  }

  /** 附加费类型 → 口径说明。 */
  public static String surchargeTypeBasis(String value) {
    if (value == null) return null;
    for (SurchargeType type : SurchargeType.values()) {
      if (type.name().equals(value)) return type.basis();
    }
    return null;
  }

  /** 附加费计算口径 → 中文名。 */
  public static String surchargeBasisLabel(String value) {
    if (value == null) return null;
    for (SurchargeBasis basis : SurchargeBasis.values()) {
      if (basis.name().equals(value)) return basis.label();
    }
    return value;
  }

  /** 附加费计算口径 → 口径说明。 */
  public static String surchargeBasisBasis(String value) {
    if (value == null) return null;
    for (SurchargeBasis basis : SurchargeBasis.values()) {
      if (basis.name().equals(value)) return basis.basis();
    }
    return null;
  }

  /** 档位计价形态 → 中文名。 */
  public static String priceModeLabel(String value) {
    if (value == null) return null;
    for (PriceMode mode : PriceMode.values()) {
      if (mode.name().equals(value)) return mode.label();
    }
    return value;
  }

  /** 档位计价形态 → 口径说明。 */
  public static String priceModeBasis(String value) {
    if (value == null) return null;
    for (PriceMode mode : PriceMode.values()) {
      if (mode.name().equals(value)) return mode.basis();
    }
    return null;
  }

  /** 头程成本项类型 → 中文名。 */
  public static String headComponentTypeLabel(String value) {
    if (value == null) return null;
    for (HeadComponentType type : HeadComponentType.values()) {
      if (type.name().equals(value)) return type.label();
    }
    return value;
  }

  /** 头程成本项类型 → 口径说明。 */
  public static String headComponentTypeBasis(String value) {
    if (value == null) return null;
    for (HeadComponentType type : HeadComponentType.values()) {
      if (type.name().equals(value)) return type.basis();
    }
    return null;
  }

  /** 头程成本项计价基数 → 中文名。 */
  public static String headCalcBasisLabel(String value) {
    if (value == null) return null;
    for (HeadCalcBasis basis : HeadCalcBasis.values()) {
      if (basis.name().equals(value)) return basis.label();
    }
    return value;
  }

  /** 头程成本项计价基数 → 口径说明。 */
  public static String headCalcBasisBasis(String value) {
    if (value == null) return null;
    for (HeadCalcBasis basis : HeadCalcBasis.values()) {
      if (basis.name().equals(value)) return basis.basis();
    }
    return null;
  }

  /** 版本状态 → 中文名。 */
  public static String statusLabel(String value) {
    if (value == null) return null;
    for (VersionStatus status : VersionStatus.values()) {
      if (status.name().equals(value)) return status.label();
    }
    return value;
  }

  /** 实际运费的来源 → 中文名。🔴 绝不写成「结算对账结果」（10 尚未交付对账事实）。 */
  public static String actualSourceLabel(String value) {
    if ("MANUAL_OBSERVATION".equals(value)) return "人工登记的物流商账单观察值";
    return value;
  }

  // ================================================================ 入参校验

  /**
   * 校验一条渠道计费规则的入参。
   *
   * <p>🔴 这道校验是为了**早点告诉人**，不是安全边界 —— DB 层的 CHECK 才是。
   * 两边的口径必须一致：服务层少判一项，就有人能写出一条 DB 层会拒的规则，
   * 然后看到一条看不懂的 23514。
   *
   * @return 问题清单；空列表 = 通过
   */
  public static List<String> validateRule(FreightRuleInput input) {
    List<String> problems = new ArrayList<>();
    if (input == null) {
      problems.add("规则内容必填");
      return problems;
    }
    if (isBlank(input.channelCode())) {
      problems.add("渠道必填：渠道计费规则挂在渠道维度上");
    }
    if (isBlank(input.shippingMethodCode())) {
      problems.add("运输方式必填：同一渠道的不同运输方式费率不同");
    }
    if (isBlank(input.chargeBasis())) {
      problems.add("计费口径必填：这一票按重量 / 体积 / 件数计必须有唯一答案");
    } else if (chargeBasisLabel(input.chargeBasis()).equals(input.chargeBasis())) {
      problems.add("计费口径不认识：" + input.chargeBasis());
    }
    if (isBlank(input.currency())) {
      problems.add("币种必填");
    }
    if (input.effectiveFrom() == null) {
      problems.add("生效起点必填：这条费率从哪一刻开始算数");
    }
    if (input.effectiveTo() != null && input.effectiveFrom() != null
        && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      problems.add("生效终点必须晚于生效起点");
    }
    if (input.volumetricDivisor() != null) {
      if (input.volumetricDivisor().signum() <= 0) {
        problems.add("体积重换算系数必须大于 0");
      }
      if (!"BY_WEIGHT".equals(input.chargeBasis())) {
        problems.add("体积重换算系数只对「按重量」计费有意义（当前口径是 "
            + chargeBasisLabel(input.chargeBasis()) + "）");
      }
    }
    if (input.minimumCharge() != null && input.minimumCharge().signum() < 0) {
      problems.add("最低收费不能为负");
    }
    problems.addAll(validateTiers(input.tiers()));
    return problems;
  }

  /**
   * 校验阶梯。
   *
   * <p>阶梯的档位必须**首尾相接且不重叠**：下界递增、上界等于下一档的下界。
   * 不相接的话，计费量落在两档之间的空隙里就没有档位可用 ——
   * 而服务层不会替用户挑一档，只会报「该规则不适用于这个计费量」。
   */
  public static List<String> validateTiers(List<FreightTierInput> tiers) {
    List<String> problems = new ArrayList<>();
    if (tiers == null || tiers.isEmpty()) {
      problems.add("至少要有一档阶梯：没有档位就永远算不出基础运费");
      return problems;
    }
    List<FreightTierInput> sorted = new ArrayList<>(tiers);
    sorted.sort(Comparator.comparingInt(FreightTierInput::seq));
    Set<Integer> seqs = new HashSet<>();
    for (int i = 0; i < sorted.size(); i++) {
      FreightTierInput tier = sorted.get(i);
      if (tier.seq() <= 0) problems.add("档位序号必须从 1 起");
      if (!seqs.add(tier.seq())) problems.add("档位序号重复：" + tier.seq());
      if (tier.minValue() == null || tier.minValue().signum() < 0) {
        problems.add("档位下界不能为负");
        continue;
      }
      if (tier.maxValue() != null && tier.maxValue().compareTo(tier.minValue()) <= 0) {
        problems.add("档位上界必须严格大于下界（第 " + tier.seq() + " 档）");
      }
      String mode = tier.priceMode() == null ? "" : tier.priceMode().trim().toUpperCase(java.util.Locale.ROOT);
      if (!"FLAT_PER_TIER".equals(mode) && !"FIRST_PLUS_ADDITIONAL".equals(mode)) {
        problems.add("档位计价形态不认识：" + tier.priceMode());
      } else if ("FLAT_PER_TIER".equals(mode)) {
        if (tier.flatPrice() == null) problems.add("整档一口价形态必须填整档价（第 " + tier.seq() + " 档）");
        else if (tier.flatPrice().signum() < 0) problems.add("整档价不能为负（第 " + tier.seq() + " 档）");
        if (tier.firstPrice() != null || tier.additionalPrice() != null || tier.firstLimit() != null) {
          problems.add("整档一口价形态不填首段 / 续段参数（第 " + tier.seq() + " 档）");
        }
      } else {
        if (tier.firstLimit() == null) {
          problems.add("首段加续段形态必须填首段上限（第 " + tier.seq() + " 档）");
        } else if (tier.firstLimit().compareTo(tier.minValue()) <= 0) {
          problems.add("首段上限必须严格大于档位下界（第 " + tier.seq() + " 档）");
        } else if (tier.maxValue() != null && tier.firstLimit().compareTo(tier.maxValue()) > 0) {
          problems.add("首段上限不能超过档位上界（第 " + tier.seq() + " 档）");
        }
        if (tier.firstPrice() == null) problems.add("首段加续段形态必须填首段单价（第 " + tier.seq() + " 档）");
        else if (tier.firstPrice().signum() < 0) problems.add("首段单价不能为负（第 " + tier.seq() + " 档）");
        if (tier.additionalPrice() == null) {
          problems.add("首段加续段形态必须填续段单价（第 " + tier.seq() + " 档）");
        } else if (tier.additionalPrice().signum() < 0) {
          problems.add("续段单价不能为负（第 " + tier.seq() + " 档）");
        }
        if (tier.flatPrice() != null) {
          problems.add("首段加续段形态不填整档价（第 " + tier.seq() + " 档）");
        }
      }
      if (i > 0) {
        FreightTierInput previous = sorted.get(i - 1);
        if (previous.maxValue() == null) {
          problems.add("第 " + previous.seq() + " 档没有上界（无上限），后面不能再有档位");
        } else if (previous.maxValue().compareTo(tier.minValue()) != 0) {
          problems.add(
              "阶梯必须首尾相接：第 "
                  + previous.seq()
                  + " 档的上界（"
                  + previous.maxValue()
                  + "）与第 "
                  + tier.seq()
                  + " 档的下界（"
                  + tier.minValue()
                  + "）之间不能有缝，否则落在这个区间里的计费量没有档位可用");
        }
      }
    }
    return problems;
  }

  /** 校验一条附加费。 */
  public static List<String> validateSurcharge(FreightSurchargeInput input) {
    List<String> problems = new ArrayList<>();
    if (input == null) {
      problems.add("附加费内容必填");
      return problems;
    }
    if (isBlank(input.channelCode())) problems.add("渠道必填");
    if (isBlank(input.shippingMethodCode())) problems.add("运输方式必填");
    if (isBlank(input.surchargeType())) {
      problems.add("附加费类型必填");
    } else if (surchargeTypeLabel(input.surchargeType()).equals(input.surchargeType())) {
      problems.add("附加费类型不认识：" + input.surchargeType());
    }
    if (isBlank(input.calcBasis())) {
      problems.add("计算口径必填");
    } else if (surchargeBasisLabel(input.calcBasis()).equals(input.calcBasis())) {
      problems.add("计算口径不认识：" + input.calcBasis());
    }
    if (isBlank(input.currency())) problems.add("币种必填");
    if (input.effectiveFrom() == null) problems.add("生效起点必填");
    if (input.effectiveTo() != null && input.effectiveFrom() != null
        && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      problems.add("生效终点必须晚于生效起点");
    }
    if ("PERCENT_OF_BASE_FREIGHT".equals(input.calcBasis())) {
      if (input.rate() == null) {
        problems.add("按基础运费比例的附加费必须填费率");
      } else if (input.rate().signum() < 0 || input.rate().compareTo(MAX_RATE) > 0) {
        problems.add("费率必须在 0 到 1 之间（小数口径：0.15 = 15%）：" + input.rate());
      }
      if (input.fixedAmount() != null) {
        problems.add("按比例的附加费不填固定额：两个都填时「加多少」没有答案");
      }
    } else if (!isBlank(input.calcBasis())) {
      if (input.fixedAmount() == null) {
        problems.add("固定额形态的附加费必须填固定额");
      } else if (input.fixedAmount().signum() < 0) {
        problems.add("固定额不能为负");
      }
      if (input.rate() != null) {
        problems.add("固定额形态的附加费不填费率：两个都填时「加多少」没有答案");
      }
    }
    return problems;
  }

  /** 校验一条头程费用结构。 */
  public static List<String> validateHeadStructure(HeadFreightStructureInput input) {
    List<String> problems = new ArrayList<>();
    if (input == null) {
      problems.add("头程费用结构内容必填");
      return problems;
    }
    if (isBlank(input.shippingMethodCode())) problems.add("运输方式必填");
    if (isBlank(input.originCode())) problems.add("起运地必填");
    if (isBlank(input.destinationCode())) problems.add("目的地必填");
    if (isBlank(input.currency())) problems.add("币种必填");
    if (input.effectiveFrom() == null) problems.add("生效起点必填");
    if (input.effectiveTo() != null && input.effectiveFrom() != null
        && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      problems.add("生效终点必须晚于生效起点");
    }
    if (input.components() == null || input.components().isEmpty()) {
      problems.add("至少要有一个成本项：没有成本项就构不成费用结构");
      return problems;
    }
    Set<Integer> seqs = new HashSet<>();
    for (var component : input.components()) {
      if (component.seq() <= 0) problems.add("成本项序号必须从 1 起");
      if (!seqs.add(component.seq())) problems.add("成本项序号重复：" + component.seq());
      if (isBlank(component.componentType())) {
        problems.add("成本项类型必填");
      } else if (headComponentTypeLabel(component.componentType()).equals(component.componentType())) {
        problems.add("成本项类型不认识：" + component.componentType());
      }
      if (isBlank(component.calcBasis())) {
        problems.add("计价基数必填");
      } else if (headCalcBasisLabel(component.calcBasis()).equals(component.calcBasis())) {
        problems.add("计价基数不认识：" + component.calcBasis());
      }
      if (component.unitAmount() == null) problems.add("单位费用必填");
      else if (component.unitAmount().signum() < 0) problems.add("单位费用不能为负");
      if (isBlank(component.currency())) problems.add("成本项币种必填");
    }
    return problems;
  }

  // ================================================================ 算式

  /** 算式需要的规则内容（与数据库解耦，便于单元测试）。 */
  public record RuleView(
      UUID id,
      String chargeBasis,
      String currency,
      BigDecimal volumetricDivisor,
      BigDecimal minimumCharge,
      List<TierView> tiers) {}

  /** 阶梯的一档。 */
  public record TierView(
      int seq,
      BigDecimal minValue,
      BigDecimal maxValue,
      String priceMode,
      BigDecimal flatPrice,
      BigDecimal firstLimit,
      BigDecimal firstPrice,
      BigDecimal additionalPrice) {}

  /** 附加费的内容。 */
  public record SurchargeView(
      UUID id,
      String surchargeType,
      String calcBasis,
      BigDecimal rate,
      BigDecimal fixedAmount,
      String currency) {}

  /** 头程成本项的内容。 */
  public record HeadComponentView(
      int seq,
      String componentType,
      String label,
      String calcBasis,
      BigDecimal unitAmount,
      String currency) {}

  /** 一次测算的输入（与数据库解耦）。 */
  public record ChargeInput(
      BigDecimal weightKg,
      BigDecimal lengthCm,
      BigDecimal widthCm,
      BigDecimal heightCm,
      BigDecimal pieces,
      BigDecimal volumetricDivisor) {}

  /**
   * 计费量（chargeable quantity）。
   *
   * @param quantity 计费量；{@code computable=false} 时为 null（**不是 0**）
   * @param unit 单位（kg / m³ / 件）
   * @param basis 算式说明（人话，直接显示）
   * @param computable 是否算得出来
   * @param reason 算不出来时的原因
   */
  public record Chargeable(
      BigDecimal quantity, String unit, String basis, boolean computable, String reason) {}

  /**
   * 基础运费。
   *
   * @param amount 算出来的基础运费；{@code applicable=false} 时为 null（**不是 0**）
   * @param currency 币种
   * @param basis 算式说明（人话，直接显示）
   * @param applicable 规则是否适用于本次计费量
   * @param note 补充说明
   */
  public record BaseFreight(
      BigDecimal amount, String currency, String basis, boolean applicable, String note) {}

  /**
   * 一条附加费套用到基础运费上的结果。
   *
   * @param amount 算出来的附加费；{@code applicable=false} 时为 null
   * @param currency 币种
   * @param rate 本次用到的费率
   * @param basis 算式说明
   * @param applicable 是否适用
   * @param note 补充说明
   */
  public record AppliedSurcharge(
      BigDecimal amount, String currency, BigDecimal rate, String basis, boolean applicable, String note) {}

  /**
   * 头程成本项套用到数量上的结果。
   *
   * @param amount 算出来的成本项金额；{@code applicable=false} 时为 null
   * @param currency 币种
   * @param basis 算式说明
   * @param applicable 是否算得出来
   * @param note 补充说明
   */
  public record AppliedHeadComponent(
      BigDecimal amount, String currency, String basis, boolean applicable, String note) {}

  /**
   * 按计费口径算计费量。
   *
   * <p>🔴 **算不出来就是 {@code computable=false} + {@code quantity=null}，绝不是 0。**
   * 「没有填实重」与「实重是 0」是两件事：前者是算不出来，后者是真的 0 公斤。
   *
   * @param basis 计费口径
   * @param input 测算输入
   */
  public static Chargeable chargeable(String basis, ChargeInput input) {
    if (basis == null) {
      return new Chargeable(null, null, "", false, "没有计费口径，算不出计费量");
    }
    switch (basis) {
      case "BY_WEIGHT": {
        if (input.weightKg() == null) {
          return new Chargeable(null, "kg", "", false, "按重量计费需要填实重（公斤）");
        }
        BigDecimal actual = input.weightKg();
        if (input.volumetricDivisor() != null
            && input.lengthCm() != null
            && input.widthCm() != null
            && input.heightCm() != null) {
          BigDecimal volumetric =
              input.lengthCm()
                  .multiply(input.widthCm())
                  .multiply(input.heightCm())
                  .divide(input.volumetricDivisor(), QUANTITY_SCALE, RoundingMode.HALF_UP);
          BigDecimal charged = actual.max(volumetric);
          return new Chargeable(
              charged,
              "kg",
              "实重 "
                  + actual.toPlainString()
                  + " kg，体积重 = 长 × 宽 × 高 ÷ "
                  + input.volumetricDivisor().toPlainString()
                  + " = "
                  + volumetric.toPlainString()
                  + " kg，计费重取两者较大者 = "
                  + charged.toPlainString()
                  + " kg",
              true,
              "");
        }
        return new Chargeable(
            actual, "kg", "实重 " + actual.toPlainString() + " kg（该渠道不按体积重计）", true, "");
      }
      case "BY_VOLUME": {
        if (input.lengthCm() == null || input.widthCm() == null || input.heightCm() == null) {
          return new Chargeable(null, "m³", "", false, "按体积计费需要填长 / 宽 / 高（厘米）");
        }
        BigDecimal cm3 = input.lengthCm().multiply(input.widthCm()).multiply(input.heightCm());
        BigDecimal m3 = cm3.divide(CM3_PER_M3, QUANTITY_SCALE, RoundingMode.HALF_UP);
        return new Chargeable(
            m3,
            "m³",
            "体积 = 长 × 宽 × 高 = "
                + cm3.toPlainString()
                + " cm³ = "
                + m3.toPlainString()
                + " m³",
            true,
            "");
      }
      case "BY_PIECE": {
        if (input.pieces() == null) {
          return new Chargeable(null, "件", "", false, "按件数计费需要填件数");
        }
        return new Chargeable(
            input.pieces(), "件", "计费量 = 件数 " + input.pieces().toPlainString() + " 件", true, "");
      }
      default:
        return new Chargeable(null, null, "", false, "未知的计费口径：" + basis);
    }
  }

  /**
   * 选档：下界闭、上界开。
   *
   * <p>{@code max_value IS NULL} = 该档以上无上限。
   * 计费量低于最低档下界 ⇒ 没有档位（规则不适用），**不是**落进第一档。
   */
  public static TierView selectTier(List<TierView> tiers, BigDecimal chargeableQuantity) {
    if (tiers == null || tiers.isEmpty()) return null;
    BigDecimal value = nz(chargeableQuantity);
    TierView best = null;
    for (TierView tier : tiers) {
      if (tier.minValue() == null) continue;
      if (value.compareTo(tier.minValue()) < 0) continue;
      if (tier.maxValue() != null && value.compareTo(tier.maxValue()) >= 0) continue;
      if (best == null || tier.minValue().compareTo(best.minValue()) > 0) best = tier;
    }
    return best;
  }

  /**
   * 按规则算出基础运费。
   *
   * <p>🔴 **算不出来就是 {@code applicable=false} + {@code amount=null}，绝不是 0。**
   *
   * @param rule 规则
   * @param chargeableQuantity 计费量
   */
  public static BaseFreight baseFreight(RuleView rule, BigDecimal chargeableQuantity) {
    if (rule == null) {
      return new BaseFreight(null, null, "", false, "没有命中的生效规则");
    }
    TierView tier = selectTier(rule.tiers(), chargeableQuantity);
    if (tier == null) {
      return new BaseFreight(
          null,
          rule.currency(),
          "阶梯计费",
          false,
          "计费量 "
              + nz(chargeableQuantity).toPlainString()
              + " 没有落进任何一档 —— 该规则不适用于这个计费量（不是运费 0）");
    }
    BigDecimal amount;
    String basis;
    if ("FLAT_PER_TIER".equals(tier.priceMode())) {
      if (tier.flatPrice() == null) {
        return new BaseFreight(null, rule.currency(), "", false, "这一档没有整档价，算不出来");
      }
      amount = money(tier.flatPrice());
      basis =
          "计费量 "
              + nz(chargeableQuantity).toPlainString()
              + " 落在档位 ["
              + tier.minValue().toPlainString()
              + ", "
              + (tier.maxValue() == null ? "∞" : tier.maxValue().toPlainString())
              + ")，整档一口价 "
              + tier.flatPrice().toPlainString()
              + " "
              + rule.currency();
    } else {
      if (tier.firstLimit() == null || tier.firstPrice() == null || tier.additionalPrice() == null) {
        return new BaseFreight(null, rule.currency(), "", false, "这一档的首段 / 续段参数不完整，算不出来");
      }
      BigDecimal value = nz(chargeableQuantity);
      BigDecimal firstEnd = tier.firstLimit().min(value);
      BigDecimal firstQty = firstEnd.subtract(tier.minValue());
      if (firstQty.signum() < 0) firstQty = BigDecimal.ZERO;
      BigDecimal firstAmount = money(firstQty.multiply(tier.firstPrice()));
      BigDecimal additionalQty = value.subtract(tier.firstLimit());
      if (additionalQty.signum() < 0) additionalQty = BigDecimal.ZERO;
      BigDecimal additionalAmount = money(additionalQty.multiply(tier.additionalPrice()));
      amount = money(firstAmount.add(additionalAmount));
      basis =
          "计费量 "
              + value.toPlainString()
              + " 落在档位 ["
              + tier.minValue().toPlainString()
              + ", "
              + (tier.maxValue() == null ? "∞" : tier.maxValue().toPlainString())
              + ")：首段 "
              + tier.minValue().toPlainString()
              + "~"
              + firstEnd.toPlainString()
              + " × "
              + tier.firstPrice().toPlainString()
              + " = "
              + firstAmount.toPlainString()
              + "，续段 "
              + (additionalQty.signum() == 0 ? "0" : tier.firstLimit().toPlainString() + "~" + value.toPlainString())
              + " × "
              + tier.additionalPrice().toPlainString()
              + " = "
              + additionalAmount.toPlainString()
              + "，基础运费 = "
              + amount.toPlainString();
    }
    String note = "";
    if (rule.minimumCharge() != null && amount.compareTo(rule.minimumCharge()) < 0) {
      amount = money(rule.minimumCharge());
      note =
          "基础运费 "
              + "低于最低收费 "
              + rule.minimumCharge().toPlainString()
              + " "
              + rule.currency()
              + "，按最低收费计";
      basis = basis + "；按最低收费 " + rule.minimumCharge().toPlainString() + " 计";
    }
    return new BaseFreight(amount, rule.currency(), basis, true, note);
  }

  /**
   * 把一条附加费套到基础运费上。
   *
   * <p>🔴 附加费一律加在**基础运费**上，不含其它附加费（避免 A 抽 B、B 抽 A 的死循环）。
   *
   * @param surcharge 附加费
   * @param baseAmount 基础运费（{@code PERCENT_OF_BASE_FREIGHT} 要用）
   * @param pieces 件数（{@code FIXED_PER_UNIT} 要用）
   */
  public static AppliedSurcharge applySurcharge(
      SurchargeView surcharge, BigDecimal baseAmount, BigDecimal pieces) {
    if (surcharge == null) {
      return new AppliedSurcharge(null, null, null, "", false, "没有命中的附加费");
    }
    if ("PERCENT_OF_BASE_FREIGHT".equals(surcharge.calcBasis())) {
      if (surcharge.rate() == null) {
        return new AppliedSurcharge(null, surcharge.currency(), null, "", false, "附加费没有费率，算不出来");
      }
      if (baseAmount == null) {
        return new AppliedSurcharge(
            null, surcharge.currency(), surcharge.rate(), "", false, "基础运费算不出来，比例附加费也算不出来");
      }
      BigDecimal amount = money(baseAmount.multiply(surcharge.rate()));
      return new AppliedSurcharge(
          amount,
          surcharge.currency(),
          surcharge.rate(),
          "基础运费 "
              + baseAmount.toPlainString()
              + " × 费率 "
              + surcharge.rate().toPlainString()
              + " = "
              + amount.toPlainString(),
          true,
          "");
    }
    if (surcharge.fixedAmount() == null) {
      return new AppliedSurcharge(null, surcharge.currency(), null, "", false, "附加费没有固定额，算不出来");
    }
    if ("FIXED_PER_UNIT".equals(surcharge.calcBasis())) {
      if (pieces == null) {
        return new AppliedSurcharge(
            null, surcharge.currency(), null, "", false, "件数算不出来，每件固定额附加费也算不出来");
      }
      BigDecimal amount = money(surcharge.fixedAmount().multiply(pieces));
      return new AppliedSurcharge(
          amount,
          surcharge.currency(),
          null,
          "每件固定额 "
              + surcharge.fixedAmount().toPlainString()
              + " × 件数 "
              + pieces.toPlainString()
              + " = "
              + amount.toPlainString(),
          true,
          "");
    }
    return new AppliedSurcharge(
        money(surcharge.fixedAmount()),
        surcharge.currency(),
        null,
        "每单固定额 " + surcharge.fixedAmount().toPlainString(),
        true,
        "");
  }

  /**
   * 把一条头程成本项套到数量上。
   *
   * @param component 成本项
   * @param shipments 票数
   * @param cbm 立方米
   * @param containers 柜数
   * @param weightKg 公斤
   */
  public static AppliedHeadComponent applyHeadComponent(
      HeadComponentView component,
      BigDecimal shipments,
      BigDecimal cbm,
      BigDecimal containers,
      BigDecimal weightKg) {
    if (component == null) {
      return new AppliedHeadComponent(null, null, "", false, "没有成本项");
    }
    BigDecimal quantity;
    String unit;
    switch (component.calcBasis() == null ? "" : component.calcBasis()) {
      case "PER_SHIPMENT" -> {
        quantity = shipments;
        unit = "票";
      }
      case "PER_CBM" -> {
        quantity = cbm;
        unit = "m³";
      }
      case "PER_CONTAINER" -> {
        quantity = containers;
        unit = "柜";
      }
      case "PER_KG" -> {
        quantity = weightKg;
        unit = "kg";
      }
      default -> {
        return new AppliedHeadComponent(
            null, component.currency(), "", false, "未知的计价基数：" + component.calcBasis());
      }
    }
    if (quantity == null) {
      return new AppliedHeadComponent(
          null, component.currency(), "", false, "缺「" + unit + "」这个数量，这一项算不出来");
    }
    BigDecimal amount = money(component.unitAmount().multiply(quantity));
    return new AppliedHeadComponent(
        amount,
        component.currency(),
        "单位费用 "
            + component.unitAmount().toPlainString()
            + " × "
            + quantity.toPlainString()
            + " "
            + unit
            + " = "
            + amount.toPlainString(),
        true,
        "");
  }

  // ================================================================ 作用域

  /** 规则的作用域人话说明（页面上要看得懂这条规则管到哪）。 */
  public static String scopeBasis(
      String channelCode,
      String channelName,
      String carrierCode,
      String shippingMethodCode,
      String shippingMethodName,
      String chargeBasis) {
    StringBuilder text = new StringBuilder("渠道 ");
    text.append(channelCode);
    if (!isBlank(channelName)) text.append("（").append(channelName).append("）");
    if (!isBlank(carrierCode)) text.append(" / 承运商 ").append(carrierCode);
    text.append(" / 运输方式 ").append(shippingMethodCode);
    if (!isBlank(shippingMethodName)) text.append("（").append(shippingMethodName).append("）");
    text.append(" / ").append(chargeBasisLabel(chargeBasis));
    return text.toString();
  }

  // ================================================================ 小工具

  /** 金额取到 4 位小数（HALF_UP）。 */
  public static BigDecimal money(BigDecimal value) {
    return nz(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 数量取到 6 位小数（HALF_UP）。 */
  public static BigDecimal quantity(BigDecimal value) {
    return nz(value).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** BigDecimal → 展示串（null 保持 null，**不**变成 "0"）。 */
  public static String text(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
