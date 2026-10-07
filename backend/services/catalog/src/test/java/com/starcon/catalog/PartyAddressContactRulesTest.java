package com.starcon.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.starcon.catalog.CatalogModels.PartyAddressUsageInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.6.3.1 地址与联系人的纯规则单测（不碰数据库）。
 *
 * <p>这里把 {@code V31__party_addresses_contacts.sql} 第九节的规则逐条钉死成断言，
 * 因为「数据库为什么这样设计」与「服务层为什么这样判」必须是同一份承诺：
 * 两处各说一套的话，下一个改代码的人只会读到其中一处。
 *
 * <p>与前端 {@code party-contact-model.test.ts} 的用例一一对应 —— 那是同一份规则的第二份实现。
 */
class PartyAddressContactRulesTest {

  // ================================================================ A1 列校验

  @Test
  void 国家必填且必须是两位大写代码() {
    assertThat(PartyAddressRules.countryProblem(null)).contains("国家不能为空");
    assertThat(PartyAddressRules.countryProblem("  ")).contains("国家不能为空");
    assertThat(PartyAddressRules.countryProblem("CHN")).contains("最多 2 个字符");
    assertThat(PartyAddressRules.countryProblem("c")).contains("两位");
    assertThat(PartyAddressRules.countryProblem("中国")).contains("两位");
    assertThat(PartyAddressRules.countryProblem(" de ")).isNull();
    assertThat(PartyAddressRules.normalizeCountry(" de ")).isEqualTo("DE");
  }

  @Test
  void 邮编可空但空白串被拒() {
    // 没有邮编体系的国家（HK / AE）不该被逼编一个假邮编。
    assertThat(PartyAddressRules.postalCodeProblem(null)).isNull();
    assertThat(PartyAddressRules.postalCodeProblem("")).isNull();
    assertThat(PartyAddressRules.postalCodeProblem("518000")).isNull();
    // 「填了但只有空格」是脏数据。
    assertThat(PartyAddressRules.postalCodeProblem("   ")).contains("空格");
    // 超过列宽上限（32）被拒。
    assertThat(PartyAddressRules.postalCodeProblem("518000123456789012345678901234567890")).contains("最多");
  }

  @Test
  void 城市与街道必填而省市区可空() {
    assertThat(PartyAddressRules.cityProblem("  ")).contains("城市不能为空");
    assertThat(PartyAddressRules.streetLine1Problem(null)).contains("街道地址不能为空");
    assertThat(PartyAddressRules.stateProvinceProblem(null)).isNull();
    assertThat(PartyAddressRules.districtProblem(null)).isNull();
    assertThat(PartyAddressRules.streetLine2Problem(" ")).contains("空格");
  }

  @Test
  void 整体校验返回第一条可执行原因() {
    assertThat(PartyAddressRules.addressProblem("", "深圳", "科技园 1 号", null, null, null, null, null, null))
        .contains("国家");
    assertThat(PartyAddressRules.addressProblem("CN", "", "科技园 1 号", null, null, null, null, null, null))
        .contains("城市");
    assertThat(PartyAddressRules.addressProblem("CN", "深圳", "  ", null, null, null, null, null, null))
        .contains("街道地址");
    assertThat(PartyAddressRules.addressProblem("CN", "深圳", "科技园 1 号", null, null, null, null, null, null))
        .isNull();
  }

  // ================================================================ A2 用途与默认

  @Test
  void 用途词表只有收货发货提货三个() {
    assertThat(PartyAddressRules.USAGES).containsExactlyInAnyOrder("SHIP_TO", "SHIP_FROM", "PICKUP");
    assertThat(PartyAddressRules.usageProblem("SHIP_TO")).isNull();
    assertThat(PartyAddressRules.usageProblem(" warehouse ")).contains("只能是");
    assertThat(PartyAddressRules.usageProblem("")).contains("不能为空");
    assertThat(PartyAddressRules.usageLabel("SHIP_TO")).isEqualTo("收货地址");
    assertThat(PartyAddressRules.usageLabel("SHIP_FROM")).isEqualTo("发货地址");
    assertThat(PartyAddressRules.usageLabel("PICKUP")).isEqualTo("提货地址");
  }

  @Test
  void 同一用途不能标两次() {
    assertThat(PartyAddressRules.usagesProblem(List.of(new PartyAddressUsageInput("SHIP_TO", false)))).isNull();
    assertThat(PartyAddressRules.usagesProblem(null)).isNull();
    assertThat(PartyAddressRules.usagesProblem(List.of())).isNull();
    assertThat(
            PartyAddressRules.usagesProblem(
                List.of(new PartyAddressUsageInput("SHIP_TO", true), new PartyAddressUsageInput("SHIP_TO", false))))
        .contains("只能标一次");
    // 三个用途全用上是合法的（真实场景：同一个仓既发货又被提货）。
    assertThat(
            PartyAddressRules.usagesProblem(
                List.of(
                    new PartyAddressUsageInput("SHIP_TO", true),
                    new PartyAddressUsageInput("SHIP_FROM", false),
                    new PartyAddressUsageInput("PICKUP", false))))
        .isNull();
  }

  @Test
  void 删除默认地址时报错并点名是哪个用途() {
    assertThat(PartyAddressRules.deleteProblem(List.of())).isNull();
    assertThat(PartyAddressRules.deleteProblem(null)).isNull();
    String problem = PartyAddressRules.deleteProblem(List.of("SHIP_TO", "PICKUP"));
    assertThat(problem).contains("默认地址").contains("收货地址").contains("提货地址");
  }

  // ================================================================ A3 内容变更判定

  @Test
  void 内容一致不追加版本而任何一列变了都追加() {
    String base =
        PartyAddressRules.contentFingerprint("CN", "广东省", "深圳市", "南山区", "518000", "科技园 1 号", "3 楼", "仓库");
    assertThat(PartyAddressRules.appendsVersion(base, base)).isFalse();
    // 换门牌号。
    assertThat(PartyAddressRules.appendsVersion(base,
            PartyAddressRules.contentFingerprint("CN", "广东省", "深圳市", "南山区", "518000", "科技园 2 号", "3 楼", "仓库")))
        .isTrue();
    // 换邮编。
    assertThat(PartyAddressRules.appendsVersion(base,
            PartyAddressRules.contentFingerprint("CN", "广东省", "深圳市", "南山区", "518001", "科技园 1 号", "3 楼", "仓库")))
        .isTrue();
    // 换地址名。
    assertThat(PartyAddressRules.appendsVersion(base,
            PartyAddressRules.contentFingerprint("CN", "广东省", "深圳市", "南山区", "518000", "科技园 1 号", "3 楼", "办公室")))
        .isTrue();
    // 国家代码大小写不同不算变更（规范化过）。
    assertThat(PartyAddressRules.appendsVersion(base,
            PartyAddressRules.contentFingerprint("cn", "广东省", "深圳市", "南山区", "518000", "科技园 1 号", "3 楼", "仓库")))
        .isFalse();
    // 清空一个可空列算变更。
    assertThat(PartyAddressRules.appendsVersion(base,
            PartyAddressRules.contentFingerprint("CN", "广东省", "深圳市", "南山区", "518000", "科技园 1 号", null, "仓库")))
        .isTrue();
    // null 与空串是同一个「没填」，不算变更（否则「清空邮编」会被判成两次变更）。
    assertThat(
            PartyAddressRules.appendsVersion(
                PartyAddressRules.contentFingerprint("CN", null, "深圳市", null, null, "科技园 1 号", null, null),
                PartyAddressRules.contentFingerprint("CN", "", "深圳市", "", "", "科技园 1 号", "", "")))
        .isFalse();
  }

  // ================================================================ A5 拉黑拦截

  @Test
  void 拉黑主体不允许新增但停用允许() {
    assertThat(PartyAddressRules.createUnderPartyProblem("BLACKLISTED", "新增地址")).contains("拉黑");
    assertThat(PartyAddressRules.createUnderPartyProblem("BLACKLISTED", "新增联系人")).contains("新增联系人");
    // 停用与可用都要能维护（「先建主体、补齐资料、再启用」是正常流程）。
    assertThat(PartyAddressRules.createUnderPartyProblem("INACTIVE", "新增地址")).isNull();
    assertThat(PartyAddressRules.createUnderPartyProblem("ACTIVE", "新增地址")).isNull();
  }

  // ================================================================ A6 选中阻断

  @Test
  void 选中阻断按删除到停用的优先级返回() {
    // 地址已删除优先于主体的一切状态。
    assertThat(
            PartyAddressRules.selectionBlock(
                new PartyAddressRules.SelectionInputs(true, true, "BLACKLISTED")))
        .isEqualTo("ADDRESS_DELETED");
    // 主体已删除优先于拉黑与停用。
    assertThat(
            PartyAddressRules.selectionBlock(
                new PartyAddressRules.SelectionInputs(false, true, "BLACKLISTED")))
        .isEqualTo("PARTY_DELETED");
    assertThat(
            PartyAddressRules.selectionBlock(
                new PartyAddressRules.SelectionInputs(false, false, "BLACKLISTED")))
        .isEqualTo("PARTY_BLACKLISTED");
    assertThat(
            PartyAddressRules.selectionBlock(new PartyAddressRules.SelectionInputs(false, false, "INACTIVE")))
        .isEqualTo("PARTY_INACTIVE");
    assertThat(
            PartyAddressRules.selectionBlock(new PartyAddressRules.SelectionInputs(false, false, "ACTIVE")))
        .isNull();
  }

  @Test
  void 四个阻断原因码的标签与处理动作互不相同() {
    var codes =
        List.of(
            PartyAddressRules.BLOCK_ADDRESS_DELETED,
            PartyAddressRules.BLOCK_PARTY_DELETED,
            PartyAddressRules.BLOCK_PARTY_INACTIVE,
            PartyAddressRules.BLOCK_PARTY_BLACKLISTED);
    assertThat(codes.stream().map(PartyAddressRules::selectionBlockLabel).distinct()).hasSize(4);
    assertThat(codes.stream().map(c -> PartyAddressRules.selectionBlockMessage(c, "深圳仓库")).distinct())
        .as("四个原因的处理动作不同，文案也不能相同")
        .hasSize(4);
    assertThat(PartyAddressRules.selectionBlockLabel(null)).isEqualTo("可选");
  }

  @Test
  void 联系人阻断少一个地址已删除分支但优先级一致() {
    assertThat(
            PartyContactRules.selectionBlock(
                new PartyContactRules.SelectionInputs(true, "BLACKLISTED")))
        .isEqualTo("PARTY_DELETED");
    assertThat(
            PartyContactRules.selectionBlock(
                new PartyContactRules.SelectionInputs(false, "BLACKLISTED")))
        .isEqualTo("PARTY_BLACKLISTED");
    assertThat(
            PartyContactRules.selectionBlock(new PartyContactRules.SelectionInputs(false, "INACTIVE")))
        .isEqualTo("PARTY_INACTIVE");
    assertThat(PartyContactRules.selectionBlock(new PartyContactRules.SelectionInputs(false, "ACTIVE"))).isNull();
    assertThat(PartyContactRules.selectionBlockLabel(null)).isEqualTo("可选");
    assertThat(PartyContactRules.selectionBlockLabel(PartyContactRules.BLOCK_PARTY_INACTIVE)).isEqualTo("主体已停用");
  }

  // ================================================================ C1 联系方式

  @Test
  void 邮箱电话手机至少给一项() {
    assertThat(PartyContactRules.reachProblem(null, null, null)).contains("至少填写一项");
    assertThat(PartyContactRules.reachProblem("", "", "")).contains("至少填写一项");
    // 「填了但只有空格」比「至少给一项」更具体，先报它：否则下面那些格式校验
    // 会把 "   " 报成「邮箱格式不正确」，让运营以为自己格式写错了。
    assertThat(PartyContactRules.reachProblem("  ", "", "   ")).contains("空格");
    assertThat(PartyContactRules.reachProblem(null, "+86 138 0000 0000", null)).isNull();
    // 任一项给了就过。
    assertThat(PartyContactRules.reachProblem("a@example.com", null, null)).isNull();
    assertThat(PartyContactRules.reachProblem(null, "+86-755-0000", null)).isNull();
    assertThat(PartyContactRules.reachProblem(null, null, "138-0000-0000")).isNull();
  }

  @Test
  void 邮箱与电话的格式校验() {
    assertThat(PartyContactRules.emailProblem("not-an-email")).contains("格式不正确");
    assertThat(PartyContactRules.emailProblem("有 空格@example.com")).contains("空格");
    assertThat(PartyContactRules.emailProblem("a@b.co")).isNull();
    assertThat(PartyContactRules.phoneProblem("abc", "电话")).contains("只能包含数字");
    assertThat(PartyContactRules.mobileProblem("+86 138 0000 0000")).isNull();
    assertThat(PartyContactRules.phoneProblem(null, "电话")).isNull();
  }

  @Test
  void 联系人的姓名必填而职务可空() {
    assertThat(PartyContactRules.nameProblem("  ")).contains("姓名不能为空");
    assertThat(PartyContactRules.nameProblem("张三")).isNull();
    assertThat(PartyContactRules.positionProblem(null)).isNull();
    assertThat(PartyContactRules.positionProblem("  ")).contains("空格");
    assertThat(PartyContactRules.contactProblem("张三", "采购", "a@b.co", null, null, null)).isNull();
    assertThat(PartyContactRules.contactProblem("", null, null, null, null, null)).contains("姓名");
  }

  @Test
  void 删除默认联系人前必须先改默认() {
    assertThat(PartyContactRules.deleteProblem(false)).isNull();
    assertThat(PartyContactRules.deleteProblem(true)).contains("默认联系人");
    // 联系人可以设默认，且拉黑状态下不可以（清理拉黑主体的联系人靠删除而不是设默认）。
    assertThat(PartyContactRules.primaryProblem("ACTIVE")).isNull();
    assertThat(PartyContactRules.primaryProblem("INACTIVE")).isNull();
    assertThat(PartyContactRules.primaryProblem("BLACKLISTED")).contains("拉黑");
  }

  // ================================================================ 展示

  @Test
  void 单行地址与用途摘要由服务端拼好() {
    assertThat(PartyAddressRules.oneLine("科技园 1 号", "3 楼", "南山区", "深圳市", "广东省", "518000", "CN"))
        .isEqualTo("科技园 1 号 3 楼 南山区 深圳市 广东省 518000 CN");
    // 空列直接跳过，不留多余空格。
    assertThat(PartyAddressRules.oneLine("中环干诺道 8 号", null, null, "香港", null, null, "HK"))
        .isEqualTo("中环干诺道 8 号 香港 HK");
    assertThat(PartyAddressRules.oneLine(null, null, null, null, null, null, "CN")).isEqualTo("CN");

    assertThat(PartyAddressRules.usageSummary(List.of(), List.of())).isEqualTo("未标用途");
    assertThat(PartyAddressRules.usageSummary(List.of("PICKUP", "SHIP_TO"), List.of("SHIP_TO")))
        .as("用途按收货 → 发货 → 提货的固定顺序展示")
        .isEqualTo("收货地址（默认） · 提货地址");
    assertThat(PartyAddressRules.orderedUsages(List.of("PICKUP", "SHIP_FROM")))
        .isEqualTo(List.of("SHIP_FROM", "PICKUP"));
  }

  @Test
  void 联系人展示名带职务() {
    assertThat(PartyContactRules.displayName("张三", "采购经理")).isEqualTo("张三（采购经理）");
    assertThat(PartyContactRules.displayName("张三", "  ")).isEqualTo("张三");
  }
}