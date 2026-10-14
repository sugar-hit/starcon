package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.CategoryNameInput;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 3.3.1.1 分类树管理的纯规则：编码与名称规范化、树种类词表、主/辅分类约束。
 *
 * <p>与 {@link SpecAxisRules} / {@link AttributeDictionaryRules} / {@link UnitRules} 同一条纪律：
 * **能在保存前判定的，就不要留到树渲染或商品保存时才炸**。树一旦成环（DB 触发器会拒）， 界面上看到的现象是「这一支点不开了」，比一个明确的 400 难排查得多。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举，与前端 {@code category-tree-model.ts} 同口径。
 *
 * <p>类名不叫 {@code CategoryTree}：那是被静态导入的 DTO record（{@link CatalogModels}）， 两者在同一批文件里会直接撞名，编译期才炸。与
 * {@link SpecAxisRules} 同一个理由。
 */
public final class CategoryTreeRules {
  private CategoryTreeRules() {}

  /** 分类编码长度上限，与 {@code product_category.code varchar(64)} 一致。 */
  public static final int MAX_CODE_LENGTH = 64;

  /** 分类名称长度上限，与 {@code product_category.name varchar(100)} 一致。 */
  public static final int MAX_NAME_LENGTH = 100;

  /** 平台编码长度上限，与 {@code product_category_tree.platform_code varchar(32)} 一致。 */
  public static final int MAX_PLATFORM_LENGTH = 32;

  /** locale 长度上限，与 {@code product_category_name.locale varchar(16)} 一致。 */
  public static final int MAX_LOCALE_LENGTH = 16;

  /** 单个分类可配置的多语言名数量上限：超过这个数基本是误操作，不是真的多语言维护。 */
  public static final int MAX_LOCALES = 20;

  /** 树种类词表：内部管理分类 / 平台类目映射。 */
  public static final Set<String> TREE_KINDS = Set.of("INTERNAL", "PLATFORM");

  /**
   * 分类编码词表：ASCII 大写字母数字与下划线短横线，1~64 位。
   *
   * <p>与 {@link CatalogService#code} 同口径：编码是刊登映射、规格轴按 code 对齐时用的**机器标识**， 不接受中文。中文类目名放在 {@code
   * name}，两者的分工与规格轴（code=COLOR / name=颜色）一致。
   */
  public static String normalizeCode(String value) {
    return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /** 分类编码校验。返回 null 表示可用；否则给出可执行的原因。 */
  public static String codeProblem(String value) {
    String code = normalizeCode(value);
    if (code.isEmpty()) return "分类编码不能为空";
    if (code.length() > MAX_CODE_LENGTH) return "分类编码最多 " + MAX_CODE_LENGTH + " 个字符";
    if (!code.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) return "分类编码请使用英文字母、数字、下划线或短横线，长度 1~64 位";
    return null;
  }

  public static String normalizeName(String value) {
    return value == null ? "" : value.trim();
  }

  /** 分类名称校验。中文类目名是常态，所以这里只管长度与非空，不管字符集。 */
  public static String nameProblem(String value) {
    String name = normalizeName(value);
    if (name.isEmpty()) return "分类名称不能为空";
    if (name.length() > MAX_NAME_LENGTH) return "分类名称最多 " + MAX_NAME_LENGTH + " 个字符";
    return null;
  }

  /** 树种类是否在词表内。 */
  public static String treeKindProblem(String value) {
    if (value == null || value.isBlank()) return "树种类不能为空";
    if (!TREE_KINDS.contains(value.trim())) return "树种类只能是 INTERNAL（内部管理分类）或 PLATFORM（平台类目映射）";
    return null;
  }

  /**
   * 平台树必须指明平台、内部树必须不指明。
   *
   * <p>与 V14 的 {@code CHECK((tree_kind='PLATFORM') = (platform_code IS NOT NULL))} 同一条口径，
   * 在服务层先给出可读原因：外键/CHECK 报出来的是约束名，界面上没法直接显示。
   */
  public static String platformProblem(String treeKind, String platformCode) {
    String kind = treeKind == null ? "" : treeKind.trim();
    String platform = normalizeName(platformCode);
    if ("PLATFORM".equals(kind)) {
      if (platform.isEmpty()) return "平台类目树必须指明所属平台（如 AMAZON / SHOPIFY）；平台树不指明平台，刊登侧取不到";
      if (platform.length() > MAX_PLATFORM_LENGTH) return "平台编码最多 " + MAX_PLATFORM_LENGTH + " 个字符";
    } else if (!platform.isEmpty()) {
      return "内部管理分类不指明平台：平台编码只属于平台类目映射树";
    }
    return null;
  }

  /** locale 规范化：去空白 + 小写。{@code zh-CN} 与 {@code zh-cn} 是同一个语言，不允许各存一条。 */
  public static String normalizeLocale(String value) {
    return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /** 单条多语言名的校验。返回 null 表示可用。 */
  public static String localeNameProblem(String locale, String name) {
    String key = normalizeLocale(locale);
    String value = normalizeName(name);
    if (key.isEmpty()) return "多语言名的语言标识不能为空（如 zh-CN / en-US）";
    if (key.length() > MAX_LOCALE_LENGTH) return "语言标识最多 " + MAX_LOCALE_LENGTH + " 个字符";
    if (!key.matches("[a-z]{2,3}(-[a-z0-9]{2,8})?"))
      return "语言标识请使用 BCP 47 形式，如 zh-CN / en-US / ja";
    if (value.isEmpty()) return "多语言名的内容不能为空";
    if (value.length() > 200) return "多语言名的内容最多 200 个字符";
    return null;
  }

  /**
   * 多语言名集合的校验：逐条合法性 + 同一语言不重复 + 数量上限。
   *
   * <p>「同一个语言两条」如果不在这里挡住，写入侧用 upsert 只会留下后写的那条， 界面上却显示「保存成功」——用户以为自己配了两套翻译。
   */
  public static String namesProblem(List<CategoryNameInput> names) {
    List<CategoryNameInput> list = names == null ? List.of() : names;
    if (list.isEmpty()) return null;
    if (list.size() > MAX_LOCALES) return "每个分类最多配置 " + MAX_LOCALES + " 个语言";
    Set<String> seen = new LinkedHashSet<>();
    for (CategoryNameInput entry : list) {
      String problem = localeNameProblem(entry.locale(), entry.name());
      if (problem != null) return problem;
      if (!seen.add(normalizeLocale(entry.locale())))
        return "语言「" + normalizeLocale(entry.locale()) + "」配置了两条：同一个语言只能有一个名称";
    }
    return null;
  }

  /** 排序位校验：负数会让同级顺序出现空洞并与 CHECK(position>=0) 对撞，这里先给可读原因。 */
  public static String positionProblem(Integer position) {
    if (position == null) return null;
    if (position < 0) return "排序位不能为负数";
    return null;
  }

  /** 角色词表：主分类 / 辅助分类。 */
  public static String roleProblem(String value) {
    if (value == null || value.isBlank()) return "分类角色不能为空";
    if (!Set.of("PRIMARY", "SECONDARY").contains(value.trim()))
      return "分类角色只能是 PRIMARY（主分类）或 SECONDARY（辅助分类）";
    return null;
  }
}
