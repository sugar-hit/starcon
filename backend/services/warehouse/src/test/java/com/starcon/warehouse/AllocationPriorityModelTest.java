package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.starcon.warehouse.AllocationPolicyModels.AllocationBufferMode;
import com.starcon.warehouse.AllocationPolicyModels.AllocationMethod;
import com.starcon.warehouse.AllocationPolicyModels.AllocationPolicyMember;
import com.starcon.warehouse.AllocationPolicyModels.AllocationPolicyRow;
import com.starcon.warehouse.AllocationPolicyModels.AllocationScopeType;
import com.starcon.warehouse.AllocationPriorityModels.FloorMode;
import com.starcon.warehouse.AllocationPriorityModels.FloorShortfallReason;
import com.starcon.warehouse.AllocationPriorityModels.FloorStatus;
import com.starcon.warehouse.AllocationPriorityModels.PriorityAllocationLine;
import com.starcon.warehouse.AllocationPriorityModels.SuppressionReason;
import com.starcon.warehouse.AllocationPriorityService.Grant;
import com.starcon.warehouse.AllocationPriorityService.Outcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 7.8.1.2 的<b>纯函数</b>单测：{@link AllocationPriorityService#compute} 的算式。
 *
 * <p>刻意不拉 Testcontainers：这个方法不碰 DB、不碰租户上下文、不读系统时间， 所以「两遍分配算得对不对」这件事可以在毫秒级里被反复验证。 DB 层的事（守恒
 * CHECK、RLS、触发器）在 {@code AllocationPriorityIntegrationTest} 里验。
 *
 * <p>每条用例都必须过 {@link #assertConservation} —— 蓝图红线要防的是超卖， 而超卖在这里就是「Σ各店分摊 &gt; 可分配量」。省掉这一步的测试等于没测。
 */
class AllocationPriorityModelTest {

  static final UUID POLICY_ID = UUID.randomUUID();
  static final UUID SHOP_A = UUID.randomUUID();
  static final UUID SHOP_B = UUID.randomUUID();
  static final UUID SHOP_C = UUID.randomUUID();

  // ------------------------------------------------------------ 夹具

  /** 一档策略。默认无缓冲（缓冲是 7.8.1.1 的口径，本模块只在算式里算一次）。 */
  static AllocationPolicyRow policy(AllocationMethod method) {
    return policy(method, AllocationBufferMode.RATIO, "0");
  }

  static AllocationPolicyRow policy(
      AllocationMethod method, AllocationBufferMode bufferMode, String bufferAmount) {
    return new AllocationPolicyRow(
        POLICY_ID,
        AllocationScopeType.DEFAULT,
        null,
        "兜底档",
        method,
        bufferMode,
        "0.000000".equals(bufferAmount) ? BigDecimal.ZERO : new BigDecimal(bufferAmount),
        "QUANTITY".equals(bufferMode.name()) ? new BigDecimal(bufferAmount) : BigDecimal.ZERO,
        "SELF_FULFILLED,SEMI_CONSIGNMENT,FULL_CONSIGNMENT,OVERSEAS_WAREHOUSE,LOCAL",
        30,
        OffsetDateTime.parse("2026-01-01T00:00:00+08:00"),
        null,
        "夹具",
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-01-01T00:00:00+08:00"));
  }

  static AllocationPolicyMember member(
      UUID shop, String mode, int priority, String shareLimit, boolean enabled) {
    return new AllocationPolicyMember(
        POLICY_ID,
        "CHANNEL",
        shop,
        "店-" + shop.toString().substring(0, 4),
        mode,
        priority,
        shareLimit == null ? null : new BigDecimal(shareLimit),
        enabled);
  }

  static Grant qty(UUID shop, String quantity, String safety) {
    return new Grant(
        shop,
        FloorMode.QUANTITY,
        new BigDecimal(quantity),
        BigDecimal.ZERO,
        new BigDecimal(safety));
  }

  static Grant ratio(UUID shop, String ratioValue, String safety) {
    return new Grant(
        shop, FloorMode.RATIO, BigDecimal.ZERO, new BigDecimal(ratioValue), new BigDecimal(safety));
  }

  /** 三家店：A 优先级最高且被停用开关控制，B 其次，C 最后。 */
  static List<AllocationPolicyMember> roster(boolean aEnabled) {
    return List.of(
        member(SHOP_A, "SELF_FULFILLED", 10, null, aEnabled),
        member(SHOP_B, "SEMI_CONSIGNMENT", 20, null, true),
        member(SHOP_C, "FULL_CONSIGNMENT", 30, null, true));
  }

  static Map<UUID, BigDecimal> sales(String a, String b, String c) {
    Map<UUID, BigDecimal> m = Map.of();
    var out = new java.util.LinkedHashMap<UUID, BigDecimal>();
    if (a != null) {
      out.put(SHOP_A, new BigDecimal(a));
    }
    if (b != null) {
      out.put(SHOP_B, new BigDecimal(b));
    }
    if (c != null) {
      out.put(SHOP_C, new BigDecimal(c));
    }
    return out;
  }

  static Outcome compute(
      AllocationPolicyRow policy,
      List<AllocationPolicyMember> roster,
      Map<UUID, BigDecimal> sales,
      List<Grant> floors,
      String available) {
    return AllocationPriorityService.compute(
        policy,
        roster,
        sales,
        floors,
        new BigDecimal(available),
        LocalDate.parse("2026-09-07"),
        LocalDate.parse("2026-10-07"));
  }

  static Map<UUID, PriorityAllocationLine> byShop(Outcome o) {
    return o.lines().stream()
        .collect(Collectors.toMap(PriorityAllocationLine::referenceId, Function.identity()));
  }

  static void assertConservation(Outcome o) {
    assertThat(o.buffer().add(o.allocatable()))
        .as("守恒式一：缓冲 + 可分配 = 可用")
        .isEqualByComparingTo(o.available());
    BigDecimal sum =
        o.lines().stream()
            .map(PriorityAllocationLine::allocatedQty)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).as("Σ各店分摊").isEqualByComparingTo(o.allocated());
    assertThat(o.allocated().add(o.unallocated()))
        .as("守恒式二：分摊合计 + 未分配 = 可分配")
        .isEqualByComparingTo(o.allocatable());
    assertThat(o.allocated()).as("Σ分摊 ≤ 可分配量，超卖要由这一条挡住").isLessThanOrEqualTo(o.allocatable());
    // 本模块自己的一条：保底池 + 余量池 = 可分配量。
    assertThat(o.floorPool().add(o.residualPool()))
        .as("本模块守恒式：保底池 + 余量池 = 可分配量")
        .isEqualByComparingTo(o.allocatable());
    for (PriorityAllocationLine line : o.lines()) {
      assertThat(line.allocatedQty().scale())
          .as("分摊额必须是整件：" + line.referenceLabel())
          .isLessThanOrEqualTo(0);
      assertThat(line.allocatedQty().signum()).as("分摊额不能为负").isNotNegative();
      assertThat(line.allocatedQty())
          .as("最终分摊 = 保底发放 + 余量分摊")
          .isEqualByComparingTo(line.floorGranted().add(line.residualQty()));
    }
  }

  // ------------------------------------------------------------ 保底裁决

  @Test
  @DisplayName("货够分时保底先保住高优先级店铺，且它不再从余量池拿第二份")
  void 保底优先且不被重复计份() {
    var o =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(qty(SHOP_A, "6", "0")),
            "100");
    assertConservation(o);
    var lines = byShop(o);
    assertThat(lines.get(SHOP_A).floorGranted()).as("A 的保底 6 件足额兑现").isEqualByComparingTo("6");
    assertThat(lines.get(SHOP_A).floorStatus()).isEqualTo(FloorStatus.FULFILLED);
    assertThat(lines.get(SHOP_A).residualQty())
        .as("保底已被足额满足的店铺不再参与余量分配 —— 否则保底就变成起步价而不是底线")
        .isEqualByComparingTo("0");
    assertThat(o.floorPool()).isEqualByComparingTo("6");
    assertThat(o.residualPool()).as("货够分，余量池就是可分配量减去保底").isEqualByComparingTo("94");
    assertThat(lines.get(SHOP_A).floorOrder()).as("A 排在仲裁次序第 1").isEqualTo(1);
    assertThat(lines.get(SHOP_C).floorOrder()).as("没配保底的店也有名次 —— 它回答的是「它本该排第几却没货保」").isEqualTo(3);
  }

  @Test
  @DisplayName("安全垫叠加在保底之上，且两者的差额都能逐项核出来")
  void 安全垫叠加在保底之上() {
    var o =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(qty(SHOP_A, "6", "4")),
            "100");
    assertConservation(o);
    var a = byShop(o).get(SHOP_A);
    assertThat(a.floorRequested()).as("请求 = 保底 6 + 安全垫 4").isEqualByComparingTo("10");
    assertThat(a.floorGranted()).isEqualByComparingTo("10");
    assertThat(a.safetyQuantity()).isEqualByComparingTo("4");
    assertThat(a.floorShortfall()).isEqualByComparingTo("0");
    assertThat(o.floorPool()).isEqualByComparingTo("10");
  }

  @Test
  @DisplayName("货不够时保底池先到先得，差额显式记成 shortfall 并写明原因")
  void 保底池不足时差额有因() {
    // 可分配 10 件，A 要 8、B 要 8：C 不参与保底。轮到 B 时池里只剩 2。
    var o =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(qty(SHOP_A, "8", "0"), qty(SHOP_B, "8", "0")),
            "10");
    assertConservation(o);
    var lines = byShop(o);
    assertThat(lines.get(SHOP_A).floorGranted()).isEqualByComparingTo("8");
    assertThat(lines.get(SHOP_A).floorStatus()).isEqualTo(FloorStatus.FULFILLED);
    assertThat(lines.get(SHOP_B).floorGranted()).as("B 只拿到池里剩的 2 件").isEqualByComparingTo("2");
    assertThat(lines.get(SHOP_B).floorRequested()).isEqualByComparingTo("8");
    assertThat(lines.get(SHOP_B).floorShortfall()).as("差额 6 件必须显式记下来").isEqualByComparingTo("6");
    assertThat(lines.get(SHOP_B).floorStatus()).isEqualTo(FloorStatus.PARTIAL);
    assertThat(lines.get(SHOP_B).shortfallReason()).isEqualTo(FloorShortfallReason.POOL_EXHAUSTED);
    assertThat(o.residualPool()).isEqualByComparingTo("0");
    assertThat(o.shortfall()).as("有 1 家店保底欠账").isEqualTo(1);
  }

  @Test
  @DisplayName("保底一件也发不出去时状态是未兑现而不是部分兑现")
  void 池子空掉时状态是未兑现() {
    // 可分配 3 件，A 要 5 → 保底池 3 件全给 A 自己，仍差 2。
    var o =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(qty(SHOP_A, "5", "0"), qty(SHOP_B, "5", "0")),
            "3");
    assertConservation(o);
    var lines = byShop(o);
    assertThat(lines.get(SHOP_A).floorGranted()).isEqualByComparingTo("3");
    assertThat(lines.get(SHOP_A).floorStatus()).isEqualTo(FloorStatus.PARTIAL);
    assertThat(lines.get(SHOP_B).floorGranted()).isEqualByComparingTo("0");
    assertThat(lines.get(SHOP_B).floorStatus())
        .as("一分没给到就是未兑现，不是部分兑现")
        .isEqualTo(FloorStatus.UNFULFILLED);
    assertThat(lines.get(SHOP_B).shortfallReason()).isEqualTo(FloorShortfallReason.POOL_EXHAUSTED);
  }

  @Test
  @DisplayName("成员被停用时保底一分不给，且原因是停用而不是池子空了")
  void 停用成员不吃保底且原因是停用() {
    var o =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(false),
            sales(null, null, null),
            List.of(qty(SHOP_A, "6", "0")),
            "100");
    assertConservation(o);
    var a = byShop(o).get(SHOP_A);
    assertThat(a.floorGranted()).isEqualByComparingTo("0");
    assertThat(a.floorRequested()).as("承诺存在过，所以请求照记").isEqualByComparingTo("6");
    assertThat(a.floorShortfall()).isEqualByComparingTo("6");
    assertThat(a.shortfallReason()).isEqualTo(FloorShortfallReason.MEMBER_DISABLED);
    assertThat(a.suppressed()).as("停用成员在无保底基线里同样是 0，所以它不是「被压低」——「停用」由保底欠账那一列表达").isFalse();
    assertThat(a.allocatedQty()).isEqualByComparingTo("0");
    assertThat(o.floorPool()).as("停用成员不占用保底池").isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("按比例保底按可分配量算，且安全垫再加在比例之外")
  void 比例保底按可分配量算() {
    var o =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(ratio(SHOP_A, "0.2", "5")),
            "200");
    assertConservation(o);
    var a = byShop(o).get(SHOP_A);
    // 可分配 200 → 20% = 40 件，安全垫再加 5 → 45。
    assertThat(a.floorRequested()).isEqualByComparingTo("45");
    assertThat(a.floorGranted()).isEqualByComparingTo("45");
    assertThat(a.floorRatio()).isEqualByComparingTo("0.2");
  }

  @Test
  @DisplayName("缓冲先扣，再在剩下的可分配量上算保底——两者串联而不是各扣一次")
  void 缓冲先扣再算保底() {
    var buffered = policy(AllocationMethod.FIXED_PRIORITY, AllocationBufferMode.RATIO, "0.10");
    var o =
        compute(
            buffered,
            roster(true),
            sales(null, null, null),
            List.of(ratio(SHOP_A, "0.5", "0")),
            "100");
    assertConservation(o);
    assertThat(o.buffer()).as("100 件留 10% = 10 件").isEqualByComparingTo("10");
    assertThat(o.allocatable()).isEqualByComparingTo("90");
    // 保底 50% 是**可分配量**的 50%，不是可用量的 50%——用可用量算就等于把缓冲扣了两次。
    assertThat(byShop(o).get(SHOP_A).floorGranted()).as("50% × 90 = 45").isEqualByComparingTo("45");
  }

  // ------------------------------------------------------------ 被压低提示

  @Test
  @DisplayName("别家店的保底把货占走时，被压低的那家带着基线与原因一起被标出来")
  void 被压低店铺带基线与原因() {
    // 无保底时 A 先到先得拿走全部 10 件。给 C 配 6 件保底后 A 只剩 4。
    var plain =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(),
            "10");
    var withFloor =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(qty(SHOP_C, "6", "0")),
            "10");

    assertConservation(plain);
    assertConservation(withFloor);
    assertThat(plain.lines())
        .as("没有保底时先到先得会让排第一的店拿走全部——这正是本模块要纠正的形态")
        .allMatch(l -> l.baseAllocatedQty().signum() >= 0);

    var a = byShop(withFloor).get(SHOP_A);
    assertThat(a.baseAllocatedQty()).as("没有保底时 A 本该拿到全部 10 件").isEqualByComparingTo("10");
    assertThat(a.allocatedQty()).isEqualByComparingTo("4");
    assertThat(a.suppressed()).as("拿到的比基线少，就是被压低").isTrue();
    assertThat(a.suppressionReason())
        .as("被别家店的保底挤下去与「本来就轮不到它」是两回事，必须标出原因")
        .isEqualTo(SuppressionReason.RESERVED_FOR_FLOOR);
    assertThat(withFloor.suppressed()).isEqualTo(1);
    assertThat(withFloor.baseAllocated()).as("基线合计留着，能与最终合计逐字作差").isEqualByComparingTo("10");
  }

  @Test
  @DisplayName("没被压低就不写原因，写了原因就一定真的比基线少")
  void 未被压低时不写原因() {
    var o =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(qty(SHOP_C, "2", "0")),
            "10");
    assertConservation(o);
    for (PriorityAllocationLine line : o.lines()) {
      if (!line.suppressed()) {
        assertThat(line.suppressionReason()).as(line.referenceLabel() + " 没被压低就不该有原因").isNull();
        assertThat(line.allocatedQty())
            .as(line.referenceLabel() + " 没被压低就必须 ≥ 基线")
            .isGreaterThanOrEqualTo(line.baseAllocatedQty());
      } else {
        assertThat(line.suppressionReason()).isNotNull();
        assertThat(line.allocatedQty()).isLessThan(line.baseAllocatedQty());
      }
    }
  }

  // ------------------------------------------------------------ 余量分配

  @Test
  @DisplayName("余量只在未被保底足额满足的店铺之间重新归一化份额")
  void 余量份额在参与店铺之间归一化() {
    // 销量 A=60 B=30 C=10（合计 100）。可分配 100，C 保底 50 足额。
    // 余量 50 只在 A 与 B 之间分，参与者销量合计 90 →
    //   A = 50×60/90 = 33.33…  → 向下取整 33
    //   B = 50×30/90 = 16.67…  → 向下取整 16
    // 逐个向下取整后合计 49，余 1 件按小数部分从大到小补发；
    // B 的小数部分 0.667 > A 的 0.333，所以补给 B → A 33、B 17。
    // 注意 C 的销量 10 仍在原始分母里，但它已被保底足额满足、不参与余量分配 ——
    // 若把它的销量留在余量的分母里，余量会被稀释到不该稀释的地方。
    var o =
        compute(
            policy(AllocationMethod.PROPORTIONAL_SALES),
            roster(true),
            sales("60", "30", "10"),
            List.of(qty(SHOP_C, "50", "0")),
            "100");
    assertConservation(o);
    var lines = byShop(o);
    assertThat(lines.get(SHOP_C).residualQty()).as("C 已被保底足额满足，不再分余量").isEqualByComparingTo("0");
    assertThat(lines.get(SHOP_C).allocatedQty()).isEqualByComparingTo("50");
    assertThat(lines.get(SHOP_A).floorGranted()).isEqualByComparingTo("0");
    assertThat(lines.get(SHOP_A).residualQty()).isEqualByComparingTo("33");
    assertThat(lines.get(SHOP_B).residualQty()).as("余数补给小数部分更大的 B").isEqualByComparingTo("17");
    assertThat(o.residualPool()).isEqualByComparingTo("50");
    assertThat(o.allocated()).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("参与店铺销量全为 0 时如实给 0，而不是把余量均分出去")
  void 销量全零不均分() {
    var o =
        compute(
            policy(AllocationMethod.PROPORTIONAL_SALES),
            roster(true),
            sales("0", "0", "0"),
            List.of(),
            "100");
    assertConservation(o);
    assertThat(o.residualPool()).isEqualByComparingTo("100");
    assertThat(o.allocated()).as("均分出来的数字没有任何依据，而界面上的数字总会被人当成依据").isEqualByComparingTo("0");
    assertThat(o.unallocated()).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("没有配任何保底时分摊结果与无保底基线逐字相同")
  void 无保底时不改变任何结果() {
    var o =
        compute(
            policy(AllocationMethod.PROPORTIONAL_SALES),
            roster(true),
            sales("60", "30", "10"),
            List.of(),
            "100");
    assertConservation(o);
    for (PriorityAllocationLine line : o.lines()) {
      assertThat(line.allocatedQty())
          .as("没配保底时不应有任何改变，否则「保底生效」就成了默认全开")
          .isEqualByComparingTo(line.baseAllocatedQty());
      assertThat(line.suppressed()).isFalse();
    }
    assertThat(o.floorPool()).isEqualByComparingTo("0");
    assertThat(o.residualPool()).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("零可用量时不发一��保底、也不报错")
  void 零可用量是合法输入() {
    var o =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(qty(SHOP_A, "6", "0")),
            "0");
    assertConservation(o);
    var a = byShop(o).get(SHOP_A);
    assertThat(a.floorGranted()).isEqualByComparingTo("0");
    assertThat(a.floorShortfall()).isEqualByComparingTo("6");
    assertThat(a.shortfallReason()).isEqualTo(FloorShortfallReason.POOL_EXHAUSTED);
  }

  @Test
  @DisplayName("同一份配置跑两次结果逐字相同——仲裁次序必须可复现")
  void 仲裁次序可复现() {
    List<AllocationPolicyMember> shuffled =
        List.of(
            member(SHOP_C, "FULL_CONSIGNMENT", 30, null, true),
            member(SHOP_A, "SELF_FULFILLED", 10, null, true),
            member(SHOP_B, "SEMI_CONSIGNMENT", 20, null, true));
    var one =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            roster(true),
            sales(null, null, null),
            List.of(qty(SHOP_A, "3", "0"), qty(SHOP_B, "3", "0")),
            "10");
    var two =
        compute(
            policy(AllocationMethod.FIXED_PRIORITY),
            shuffled,
            sales(null, null, null),
            List.of(qty(SHOP_A, "3", "0"), qty(SHOP_B, "3", "0")),
            "10");
    assertThat(two.lines())
        .as("名册顺序不同不应改变结果，次序由配置决定")
        .usingRecursiveComparison()
        .ignoringFields("salesUnits")
        .isEqualTo(one.lines());
    assertConservation(one);
    assertConservation(two);
  }
}
