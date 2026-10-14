package com.starcon.catalog;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 3.3.2.1 品牌管理的纯规则：编码与名称规范化、授权状态词表、多语言名校验、 凭证有效期判定，以及蓝图点名的**阻止刊登判定**。
 *
 * <p>与 {@link CategoryTreeRules} / {@link SpecAxisRules} / {@link AttributeDictionaryRules} /
 * {@link UnitRules} 同一条纪律：**能在保存前判定的，就不要留到刊登时才炸**。 「需授权品牌没有有效凭证」这件事如果只在刊登时才发现，代价是一批已经排好的货；
 * 而在品牌页就能当场看见「这个品牌现在登不了，缺一张有效凭证」，代价只是一行提示。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举，与前端 {@code brand-model.ts} 同口径。
 *
 * <p>类名不叫 {@code Brand}：那是被静态导入的 DTO record（{@link CatalogModels}）， 两者在同一批文件里会直接撞名，编译期才炸。与 {@link
 * CategoryTreeRules} 同一个理由。
 */
public final class BrandRules {
  private BrandRules() {}

  /** 品牌编码长度上限，与 {@code brand.code varchar(64)} 一致。 */
  public static final int MAX_CODE_LENGTH = 64;

  /** 品牌名称长度上限，与 {@code brand.name varchar(100)} 一致。 */
  public static final int MAX_NAME_LENGTH = 100;

  /** locale 长度上限，与 {@code brand_name.locale varchar(16)} 一致。 */
  public static final int MAX_LOCALE_LENGTH = 16;

  /** Logo 引用长度上限，与 {@code brand.logo_uri varchar(500)} 一致。 */
  public static final int MAX_LOGO_LENGTH = 500;

  /** 凭证编号长度上限，与 {@code brand_authorization.certificate_no varchar(64)} 一致。 */
  public static final int MAX_CERTIFICATE_NO_LENGTH = 64;

  /** 授权方长度上限，与 {@code brand_authorization.grantor varchar(150)} 一致。 */
  public static final int MAX_GRANTOR_LENGTH = 150;

  /** 凭证材料引用长度上限，与 {@code brand_authorization.evidence_uri varchar(500)} 一致。 */
  public static final int MAX_EVIDENCE_LENGTH = 500;

  /** 单个品牌可配置的多语言名数量上限：超过这个数基本是误操作。 */
  public static final int MAX_LOCALES = 20;

  /** 授权状态词表：自有 / 代理 / 需授权。 */
  public static final Set<String> AUTHORIZATION_STATUSES = Set.of("OWNED", "AGENCY", "LICENSED");

  /**
   * 阻止刊登的原因码。返回体里给机器读的是它，给运营看的是 {@link #blockMessage}。
   *
   * <p>用码而不是一句话做接口字段：04 刊登与 16 侵权检测要按「缺凭证」「凭证过期」 走**不同的处理**（前者提示补材料，后者要立刻下架该品牌下的商品）。
   * 只返回一句中文的话，两个下游只能去匹配字符串。
   */
  public static final String BLOCK_BRAND_DISABLED = "BRAND_DISABLED";

  public static final String BLOCK_LICENSE_MISSING = "LICENSE_MISSING";
  public static final String BLOCK_LICENSE_NOT_YET_EFFECTIVE = "LICENSE_NOT_YET_EFFECTIVE";
  public static final String BLOCK_LICENSE_EXPIRED = "LICENSE_EXPIRED";

  /** 品牌编码规范化：去空白 + 大写。编码是刊登与侵权检测对齐用的机器标识，不接受中文。 */
  public static String normalizeCode(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  /** 品牌编码校验。返回 null 表示可用；否则给出可执行的原因。 */
  public static String codeProblem(String value) {
    String code = normalizeCode(value);
    if (code.isEmpty()) return "品牌编码不能为空";
    if (code.length() > MAX_CODE_LENGTH) return "品牌编码最多 " + MAX_CODE_LENGTH + " 个字符";
    if (!code.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) return "品牌编码请使用英文字母、数字、下划线或短横线，长度 1~64 位";
    return null;
  }

  public static String normalizeName(String value) {
    return value == null ? "" : value.trim();
  }

  /** 品牌名称校验。中文品牌名是常态（甚至比英文多），所以这里只管长度与非空。 */
  public static String nameProblem(String value) {
    String name = normalizeName(value);
    if (name.isEmpty()) return "品牌名称不能为空";
    if (name.length() > MAX_NAME_LENGTH) return "品牌名称最多 " + MAX_NAME_LENGTH + " 个字符";
    return null;
  }

  /** 授权状态是否在词表内。 */
  public static String authorizationStatusProblem(String value) {
    if (value == null || value.isBlank()) return "授权状态不能为空";
    if (!AUTHORIZATION_STATUSES.contains(value.trim()))
      return "授权状态只能是 OWNED（自有）、AGENCY（代理）或 LICENSED（需授权）";
    return null;
  }

  public static String normalizeStatus(String value) {
    return value == null ? "" : value.trim();
  }

  /** Logo 引用校验：可空，但填了就必须是可用的引用（不能是空白串）。 */
  public static String logoProblem(String value) {
    String logo = value == null ? "" : value.trim();
    if (logo.length() > MAX_LOGO_LENGTH) return "Logo 引用最多 " + MAX_LOGO_LENGTH + " 个字符";
    if (logo.contains(" ")) return "Logo 引用不能包含空格，请填对象存储 key 或完整 URL";
    return null;
  }

  /** locale 规范化：去空白 + 小写。{@code zh-CN} 与 {@code zh-cn} 是同一个语言。 */
  public static String normalizeLocale(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
   * 一整组多语言名的校验：逐条校验 + 两条集合级约束。
   *
   * <p>集合级约束只在服务层做（{@code brand_name} 的主键 {@code (tenant_id,brand_id,locale)}
   * 已经挡住重复写入，但重复提交会变成一条约束冲突，界面上只看到一句 500）：
   *
   * <ul>
   *   <li>同一个语言只能有一条（规范化之后比较，{@code zh-CN} 与 {@code zh-cn} 算同一个）；
   *   <li>数量上限（{@link #MAX_LOCALES}）——20 条语言名意味着维护不过来了。
   * </ul>
   */
  public static String namesProblem(List<CatalogModels.BrandNameInput> names) {
    if (names == null || names.isEmpty()) return null;
    if (names.size() > MAX_LOCALES) return "单个品牌最多配置 " + MAX_LOCALES + " 个语言名";
    Set<String> seen = new LinkedHashSet<>();
    for (CatalogModels.BrandNameInput entry : names) {
      if (entry == null) return "多语言名列表里不能有空项";
      String problem = localeNameProblem(entry.locale(), entry.name());
      if (problem != null) return problem;
      if (!seen.add(normalizeLocale(entry.locale())))
        return "同一个语言只能有一个品牌名：" + normalizeLocale(entry.locale());
    }
    return null;
  }

  // ============================================================ 凭证

  public static String normalizeText(String value) {
    return value == null ? "" : value.trim();
  }

  /** 凭证编号校验。 */
  public static String certificateNoProblem(String value) {
    String no = normalizeText(value);
    if (no.isEmpty()) return "凭证编号不能为空";
    if (no.length() > MAX_CERTIFICATE_NO_LENGTH)
      return "凭证编号最多 " + MAX_CERTIFICATE_NO_LENGTH + " 个字符";
    return null;
  }

  /** 授权方校验。 */
  public static String grantorProblem(String value) {
    String grantor = normalizeText(value);
    if (grantor.isEmpty()) return "授权方不能为空";
    if (grantor.length() > MAX_GRANTOR_LENGTH) return "授权方最多 " + MAX_GRANTOR_LENGTH + " 个字符";
    return null;
  }

  /**
   * 凭证有效期校验。
   *
   * <p>{@code validTo} 为空表示长期有效，不是漏填——自有品牌的历史合约经常没有终止日。 但填了就不能早于生效日：那种行写进库会让「有效区间」在界面上显示成倒着的，
   * 而且没人会注意到它被判成了永远无效。
   */
  public static String validityProblem(LocalDate from, LocalDate to) {
    if (from == null) return "凭证生效日期不能为空";
    if (to != null && to.isBefore(from)) return "凭证失效日期不能早于生效日期";
    return null;
  }

  /** 凭证材料引用校验：可空，填了不能超长。 */
  public static String evidenceProblem(String value) {
    String evidence = normalizeText(value);
    if (evidence.length() > MAX_EVIDENCE_LENGTH) return "凭证材料引用最多 " + MAX_EVIDENCE_LENGTH + " 个字符";
    return null;
  }

  /**
   * 一条凭证在指定日期是否有效。
   *
   * <p>判定与 {@link #publishBlock} 同口径，两处各写一遍的话， 页面说「有效」而刊登判定说「过期」的那类分裂 bug 要等到出事才发现。
   *
   * <p>{@code enabled=false} 的凭证一律无效：撤销一张授权不该靠删行来表达—— 删了就查不到「曾经有过授权、后来被撤销」，而 16 侵权检测恰恰要这个事实。
   */
  public static boolean validOn(boolean enabled, LocalDate from, LocalDate to, LocalDate on) {
    if (!enabled || from == null || on == null) return false;
    if (on.isBefore(from)) return false;
    return to == null || !on.isAfter(to);
  }

  // ============================================================ 阻止刊登判定（蓝图点名的业务规则）

  /** 一次凭证判定所需的全部输入。抽成 record 是为了让纯规则不依赖数据库行。 */
  public record PublishInputs(
      boolean enabled, String authorizationStatus, List<Credential> credentials, LocalDate asOf) {}

  /** 一条凭证的判定输入：启用状态与有效期。 */
  public record Credential(boolean enabled, LocalDate validFrom, LocalDate validTo) {}

  /**
   * 判定一个品牌当前能否刊登。
   *
   * <p>蓝图原话：「品牌授权状态直接影响能否刊登，品牌『需授权』但无有效凭证时应阻止发布」。 落成四条原因码，判定顺序即优先级：
   *
   * <ol>
   *   <li>品牌已停用 → {@link #BLOCK_BRAND_DISABLED}。停用的品牌在任何授权状态下都登不了， 否则「停用」这个动作就失去意义（它只剩界面的视觉意义）。
   *   <li>状态不是 LICENSED（自有 / 代理）→ 恒可刊登。这两个状态本身就是「已有授权」， 再要求凭证就等于逼运营给自有品牌伪造一份授权书。
   *   <li>需授权且一条凭证都没有 → {@link #BLOCK_LICENSE_MISSING}。
   *   <li>需授权、有凭证但当前都不在有效期内 → {@link #BLOCK_LICENSE_NOT_YET_EFFECTIVE}（有凭证、最早一条还没到生效日）或 {@link
   *       #BLOCK_LICENSE_EXPIRED}（有过期日、已经全过期）。 两者分开是因为处理动作不同：前者「等着」，后者「要续约或换品牌」。
   * </ol>
   *
   * <p>被停用的凭证（{@code enabled=false}）不参与判定：撤销就该等于无效， 但行还在，可查——理由见 {@link #validOn}。
   *
   * @return 可刊登返回 null，否则返回原因码
   */
  public static String publishBlock(PublishInputs input) {
    if (!input.enabled()) return BLOCK_BRAND_DISABLED;
    if (!"LICENSED".equals(normalizeStatus(input.authorizationStatus()))) return null;
    List<Credential> all =
        input.credentials() == null ? List.of() : input.credentials().stream().toList();
    if (all.isEmpty()) return BLOCK_LICENSE_MISSING;
    LocalDate asOf = input.asOf();
    boolean anyValid = false;
    for (Credential credential : all) {
      if (validOn(credential.enabled(), credential.validFrom(), credential.validTo(), asOf))
        anyValid = true;
    }
    if (anyValid) return null;
    // 全部不在有效期内：区分「还没到」与「已过期」——两个下游要走的处理不一样。
    boolean anyFuture = false;
    for (Credential credential : all) {
      LocalDate from = credential.validFrom();
      if (from != null && asOf != null && asOf.isBefore(from)) anyFuture = true;
    }
    return anyFuture ? BLOCK_LICENSE_NOT_YET_EFFECTIVE : BLOCK_LICENSE_EXPIRED;
  }

  /** 阻止原因码对应的运营提示。null 表示可刊登。 */
  public static String blockMessage(String code, String brandName) {
    String name = normalizeText(brandName);
    String subject = name.isEmpty() ? "该品牌" : "品牌【" + name + "】";
    if (code == null) return null;
    return switch (code) {
      case BLOCK_BRAND_DISABLED -> subject + "已停用，请先启用再刊登";
      case BLOCK_LICENSE_MISSING -> subject + "标记为需授权，但没有任何授权凭证，不能刊登";
      case BLOCK_LICENSE_NOT_YET_EFFECTIVE -> subject + "的授权凭证尚未到生效日期，不能刊登";
      case BLOCK_LICENSE_EXPIRED -> subject + "的授权凭证已全部过期，请续约后再刊登";
      default -> subject + "当前不允许刊登";
    };
  }

  /** 阻止原因码的中文标签，供列表页筛选与统计用。 */
  public static String blockLabel(String code) {
    if (code == null) return "可刊登";
    return switch (code) {
      case BLOCK_BRAND_DISABLED -> "品牌停用";
      case BLOCK_LICENSE_MISSING -> "缺少凭证";
      case BLOCK_LICENSE_NOT_YET_EFFECTIVE -> "凭证未生效";
      case BLOCK_LICENSE_EXPIRED -> "凭证已过期";
      default -> "不可刊登";
    };
  }
}
