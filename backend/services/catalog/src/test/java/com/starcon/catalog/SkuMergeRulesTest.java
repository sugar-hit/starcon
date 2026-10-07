package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.SkuMergeRules.RefPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SkuMergeRules} 的纯规则测试（不碰数据库）。
 *
 * <p>集成测试证明的是「规则被真实数据正确应用了」；这一组证明的是<b>规则本身</b>——
 * 尤其是几处只要写错就<b>静默</b>的判断：冲突判定用 {@code =} 而不是
 * {@code is not distinct from} 会漏检可空键列的冲突、{@link RefPolicy#skuCol()} 写死
 * {@code sku_id} 会让 {@code bom} 与 {@code bom_explosion_run} 两个维度统计成 0、
 * 词表里少了某个维度会让「这一类引用」在报告上凭空消失。
 */
class SkuMergeRulesTest {

  @Test
  void 每个维度的词表项都唯一且非空() {
    List<RefPolicy> all = SkuMergeRules.allPolicies();
    assertThat(all).isNotEmpty();
    assertThat(all.stream().map(RefPolicy::dimension)).doesNotHaveDuplicates();
    assertThat(all).allSatisfy(p -> assertThat(p.label()).isNotBlank());
    // 搬得动的维度排在冻结的历史之前 —— 页面展示顺序依赖这个
    int lastMove = -1;
    int firstFrozen = all.size();
    for (int i = 0; i < all.size(); i++) {
      if (SkuMergeRules.FROZEN.equals(all.get(i).action())) {
        firstFrozen = i;
        break;
      }
      lastMove = i;
    }
    assertThat(lastMove).isLessThan(firstFrozen);
  }

  @Test
  void 维度词表与V41的CHECK一致() {
    // 落库列 dimension 有 CHECK 约束；应用层多一个少一个都会在运行时炸或静默丢报告行
    assertThat(SkuMergeRules.allPolicies().stream().map(RefPolicy::dimension))
        .containsExactlyInAnyOrder(
            "BARCODE", "ATTRIBUTE", "MEDIA", "TRANSLATION", "QUOTE", "BUNDLE", "BUNDLE_SKU",
            "BOM_OUTPUT", "BOM", "PACKAGE", "SAFETY", "UNIT_RULE",
            "UNIT_CONVERSION_SNAPSHOT", "STATUS_EVENT", "IMPORT_HISTORY", "TITLE_HISTORY",
            "RENDER_ARTIFACT", "TRANSLATION_HISTORY", "BOM_EXPLOSION_HISTORY", "CODE_HISTORY",
            "DUPLICATE_HISTORY");
  }

  @Test
  void 指向sku的列名逐个写对() {
    // bom 说的是「产出哪个 SKU」、bom_explosion_run 同义，两者的列都不是 sku_id。
    // 写死 sku_id 会让这两个维度在报告上显示「无引用」—— 那比报错危险得多。
    assertThat(SkuMergeRules.policy("BOM_OUTPUT").skuCol()).isEqualTo("output_sku_id");
    assertThat(SkuMergeRules.policy("BOM").skuCol()).isEqualTo("sku_id");
    assertThat(SkuMergeRules.policy("BOM").table()).isEqualTo("bom_component");
    // 其余一律 sku_id
    assertThat(SkuMergeRules.MOVE_POLICIES)
        .filteredOn(p -> !p.dimension().equals("BOM_OUTPUT"))
        .allSatisfy(p -> assertThat(p.skuCol()).isEqualTo("sku_id"));
  }

  @Test
  void 冻结维度用对各自的计数列() {
    // 3.9.1.1 的两张表没有 sku_id，它们记的是「这一对」
    var dup = SkuMergeRules.policy("DUPLICATE_HISTORY");
    assertThat(dup.frozenSources()).hasSize(2);
    assertThat(dup.frozenSources()).allSatisfy(r -> assertThat(r.sideCols()).isNotNull());
    assertThat(dup.frozenSources().get(0).sideCols()).containsExactly("left_sku_id", "right_sku_id");
    assertThat(dup.frozenSources().get(1).sideCols())
        .containsExactly("primary_sku_id", "secondary_sku_id");
    // 一次展开的运行头记的是成品 SKU（列名是 output_sku_id，不是 sku_id）
    List<SkuMergeRules.TableRef> explosion = SkuMergeRules.policy("BOM_EXPLOSION_HISTORY").frozenSources();
    assertThat(explosion.stream().map(SkuMergeRules.TableRef::skuCol).toList())
        .containsExactly("output_sku_id", null);
    // 其余冻结维度都按单列 sku_id 计数（sideCols 留空）
    List<SkuMergeRules.TableRef> plain = new java.util.ArrayList<>();
    for (RefPolicy p : SkuMergeRules.FROZEN_POLICIES) {
      if (!p.dimension().equals("DUPLICATE_HISTORY")) plain.addAll(p.frozenSources());
    }
    assertThat(plain).isNotEmpty();
    for (SkuMergeRules.TableRef r : plain) {
      assertThat(r.sideCols()).as("%s 的计数列", r.table()).isNull();
    }
  }

  @Test
  void 只有组件类维度才相加只有素材才去重() {
    assertThat(SkuMergeRules.MOVE_POLICIES)
        .filteredOn(p -> SkuMergeRules.SUM.equals(p.action()))
        .extracting(RefPolicy::dimension)
        .containsExactlyInAnyOrder("BUNDLE", "BOM");
    assertThat(SkuMergeRules.MOVE_POLICIES)
        .filteredOn(RefPolicy::dropConflicts)
        .extracting(RefPolicy::dimension)
        .containsExactly("MEDIA");
    // 相加的维度必须给出要相加的列
    assertThat(SkuMergeRules.MOVE_POLICIES)
        .filteredOn(p -> SkuMergeRules.SUM.equals(p.action()))
        .allSatisfy(p -> assertThat(p.qtyCol()).isNotBlank());
  }

  @Test
  void 只有属性值限制在同Spu() {
    // attribute_value 的外键是 (tenant_id,spu_id,sku_id) -> sku，跨 SPU 改指会指向不存在的组合
    assertThat(SkuMergeRules.MOVE_POLICIES).filteredOn(RefPolicy::sameSpuOnly)
        .extracting(RefPolicy::dimension).containsExactly("ATTRIBUTE");
  }

  @Test
  void 冲突判定用Null安全比较() {
    // 键列可空（supplier_quote.valid_to、sku_unit_conversion.effective_to），
    // 用 `=` 的话 NULL=NULL 为假，冲突会被漏检，然后由唯一约束在更晚的地方炸成 500。
    assertThat(SkuMergeRules.policy("QUOTE").conflictJoin())
        .isEqualTo("t.supplier_role_id is not distinct from s.supplier_role_id"
            + " and t.supplier_sku_code is not distinct from s.supplier_sku_code");
    assertThat(SkuMergeRules.policy("UNIT_RULE").conflictJoin())
        .isEqualTo("t.unit_code is not distinct from s.unit_code"
            + " and t.role is not distinct from s.role"
            + " and t.effective_from is not distinct from s.effective_from");
    // 空键 = 「主记录上只要有该表任何一行就算冲突」（UNIQUE(tenant_id,sku_id) 那几张）
    assertThat(SkuMergeRules.policy("PACKAGE").conflictJoin()).isEqualTo("true");
    assertThat(SkuMergeRules.policy("SAFETY").conflictJoin()).isEqualTo("true");
    assertThat(SkuMergeRules.policy("BUNDLE_SKU").conflictJoin()).isEqualTo("true");
  }

  @Test
  void 版本比对六种结论各自互不相同() {
    // 用 List 而不是 Set.of：Set.of 遇到重复元素会直接抛 IllegalArgument，
    // 而这里 ALREADY_MERGED 故意出现两次（主记录端 / 被合并记录端）。
    List<String> all = List.of(
        SkuMergeRules.versionCheck(null, false, false, false, 1L, 1L, 1, 1),
        SkuMergeRules.versionCheck("KEEP", false, false, false, null, null, 1, 1),
        SkuMergeRules.versionCheck("MERGE", true, false, false, 1L, 1L, 1, 1),
        SkuMergeRules.versionCheck("MERGE", false, true, false, 1L, 1L, 1, 1),
        SkuMergeRules.versionCheck("MERGE", false, false, true, 1L, 1L, 1, 1),
        SkuMergeRules.versionCheck("MERGE", false, false, false, 1L, 1L, 2, 1),
        SkuMergeRules.versionCheck("MERGE", false, false, false, 1L, 1L, 1, 1));
    // 七个场景、六种结论：两端任一已被合并都归到 ALREADY_MERGED，所以去重后是 6
    assertThat(all.stream().distinct()).hasSize(6);
    // 六个词表常量互不相同（否则「结论不同」这句话本身没有意义）
    assertThat(List.of(
            SkuMergeRules.CHECK_MATCHED, SkuMergeRules.CHECK_STALE,
            SkuMergeRules.CHECK_ALREADY_EXECUTED, SkuMergeRules.CHECK_NOT_DECIDED,
            SkuMergeRules.CHECK_ALREADY_MERGED, SkuMergeRules.CHECK_KEEP))
        .doesNotHaveDuplicates();
  }

  @Test
  void 版本比对逐个场景() {
    // 对得上才放行
    assertThat(SkuMergeRules.versionCheck("MERGE", false, false, false, 3L, 4L, 3, 4))
        .isEqualTo(SkuMergeRules.CHECK_MATCHED);
    // 主记录被改过 / 被合并记录被改过，两个方向都要能拦住
    assertThat(SkuMergeRules.versionCheck("MERGE", false, false, false, 3L, 4L, 5, 4))
        .isEqualTo(SkuMergeRules.CHECK_STALE);
    assertThat(SkuMergeRules.versionCheck("MERGE", false, false, false, 3L, 4L, 3, 7))
        .isEqualTo(SkuMergeRules.CHECK_STALE);
    // 已经执行过
    assertThat(SkuMergeRules.versionCheck("MERGE", true, false, false, 3L, 4L, 3, 4))
        .isEqualTo(SkuMergeRules.CHECK_ALREADY_EXECUTED);
    // 判成保留两条：不该有任何归并动作
    assertThat(SkuMergeRules.versionCheck("KEEP", false, false, false, null, null, 3, 4))
        .isEqualTo(SkuMergeRules.CHECK_KEEP);
    // 还没人判
    assertThat(SkuMergeRules.versionCheck(null, false, false, false, null, null, 3, 4))
        .isEqualTo(SkuMergeRules.CHECK_NOT_DECIDED);
    // 合并链保持一层：两端任一已被合并过就不再允许参与
    assertThat(SkuMergeRules.versionCheck("MERGE", false, true, false, 3L, 4L, 3, 4))
        .isEqualTo(SkuMergeRules.CHECK_ALREADY_MERGED);
    assertThat(SkuMergeRules.versionCheck("MERGE", false, false, true, 3L, 4L, 3, 4))
        .isEqualTo(SkuMergeRules.CHECK_ALREADY_MERGED);
    // 「已执行过」优先于「已被合并」：它对运营更有解释力
    assertThat(SkuMergeRules.versionCheck("MERGE", true, true, true, 3L, 4L, 3, 4))
        .isEqualTo(SkuMergeRules.CHECK_ALREADY_EXECUTED);
  }

  @Test
  void 每种版本比对结论都有一句能给人看的话() {
    for (String check :
        List.of(
            SkuMergeRules.CHECK_MATCHED, SkuMergeRules.CHECK_STALE,
            SkuMergeRules.CHECK_ALREADY_EXECUTED, SkuMergeRules.CHECK_NOT_DECIDED,
            SkuMergeRules.CHECK_ALREADY_MERGED, SkuMergeRules.CHECK_KEEP)) {
      assertThat(SkuMergeRules.versionCheckLabel(check)).isNotBlank();
      assertThat(SkuMergeRules.versionCheckLabel(check)).isNotEqualTo(check);
    }
  }

  @Test
  void 执行范围与动作的中文名都齐备() {
    assertThat(SkuMergeRules.scopeLabel("SKU")).contains("同 SPU");
    assertThat(SkuMergeRules.scopeLabel("SPU")).contains("停产");
    assertThat(SkuMergeRules.actionLabel(SkuMergeRules.MOVE)).isNotBlank();
    assertThat(SkuMergeRules.actionLabel(SkuMergeRules.SUM)).contains("相加");
    assertThat(SkuMergeRules.actionLabel(SkuMergeRules.FROZEN)).contains("冻结");
    // 跨 SPU 时源商品上还有别的 SKU，提示必须说清「它们没被搬」这件事
    assertThat(SkuMergeRules.crossSpuNote(0)).contains("空壳");
    assertThat(SkuMergeRules.crossSpuNote(3)).contains("3").contains("一条都没动");
  }
}
