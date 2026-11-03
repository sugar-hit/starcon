package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * **crm 服务用到的每个权限码，都必须在 identity 服务的 {@code permission} 表里登记过。**
 *
 * <p>为什么这条要单独钉：{@code role_permission.permission_code} 上有指向
 * {@code permission(code)} 的**外键** ⇒ 一个权限码没登记进 {@code permission} 表，
 * 就**没有任何角色能持有它**（授权写入被外键拒绝），而 {@code access.require(bearer, code)}
 * 按「已认证成员持有的权限码」判定 ⇒ 端点在真实部署里恒定 403。
 *
 * <p>🔴 这类缺陷**任何一层的测试都不会红**：crm 的集成测试直接往访问上下文里塞权限码
 * （桩住 identity），走的是「这个角色**能**持有这些权限」的假设；而真正的问题是
 * 「identity 的 permission 表里**根本没有这些码**」。
 * 2026-11-02 的 {@code docs/backlog/2026-11-02-identity-has-no-crm-permission-codes.md}
 * 记的就是这个缺口 —— V432 只登记了 {@code crm.customers} 一族，此后交付的 20 个 crm
 * 模块要的码**从来没进过 identity**，21 个族里 20 个族的端点在真实部署中全部 403。
 *
 * <p>本守卫从**本模块自己的源码**反向抽出真实的权限码，再去 identity 的迁移里核对登记，
 * 与 api-proxy 白名单守卫同一手法（不写死清单 —— 写死的清单只会验证「清单与登记一致」，
 * 而真正会出事的是「源码加了新族、identity 忘了登记」）。
 *
 * <p>跨服务**只读源码文件**（构建期静态比对），不连对方的库 —— AGENTS.md 禁止跨库查询。
 */
class CrmPermissionCodesGuardTest {

  /** 本模块源码里权限前缀的写法：{@code "crm.<族>." + permission}。 */
  private static final Pattern PREFIX =
      Pattern.compile("\"(crm\\.[a-z_.]*)\"\\s*\\+\\s*permission");

  /**
   * 本模块源码里 action 字面量的写法：{@code call(auth, "read", …)}。
   *
   * <p>🔴 只认 {@code call(} / {@code callVoid(} 这两个入口：它们是各控制器统一调用
   * {@code access.require} 的地方。漏了它们会漏掉一整族权限码。
   */
  private static final Pattern ACTION =
      Pattern.compile("\\bcall(?:Void)?\\(\\s*[A-Za-z_][A-Za-z0-9_.()]*\\s*,\\s*\"([a-zA-Z.]+)\"");

  /**
   * 本模块源码里**字面量**权限码（如 {@code "crm.customers.pii.read"}）。
   *
   * <p>有些码不进 {@code access.require}，而是在服务层做**第二道**校验
   * （拿到 {@code crm.customers.read} 之后，再看这个成员有没有
   * {@code crm.customers.pii.read} 才给明文联系方式）⇒ 只扫前缀会漏掉它们。
   */
  private static final Pattern LITERAL = Pattern.compile("\"(crm\\.[a-z_.]+)\"");

  /** identity 迁移里 {@code INSERT INTO permission} 的两种写法。 */
  private static final Pattern SELECT_FORM =
      Pattern.compile("'(crm\\.[a-z_.]+)\\.?'?\\s*\\|\\|\\s*action");

  private static final Pattern ARRAY_FORM = Pattern.compile("ARRAY\\s*\\[([^\\]]*)\\]");

  private static final Pattern VALUES_FORM =
      Pattern.compile("'(crm\\.[a-z_.]+)'\\s*,\\s*'crm\\.[a-z_.]+'\\s*,\\s*'[a-z.]+'");

  /** 本模块源码目录（surefire 的工作目录是模块根；退路是后端根）。 */
  private static Path moduleSources() {
    for (String candidate : new String[] {"src/main/java", "services/crm/src/main/java"}) {
      Path p = Path.of(candidate);
      if (Files.isDirectory(p)) return p;
    }
    throw new IllegalStateException("找不到 crm 模块源码目录（cwd=" + Path.of(".").toAbsolutePath() + "）");
  }

  /** identity 服务的迁移目录（permission 表的写入所有者）。 */
  private static Path identityMigrations() {
    for (String candidate :
        new String[] {
          "../identity/src/main/resources/db/migration",
          "services/identity/src/main/resources/db/migration"
        }) {
      Path p = Path.of(candidate);
      if (Files.isDirectory(p)) return p;
    }
    throw new IllegalStateException(
        "找不到 identity 迁移目录（cwd=" + Path.of(".").toAbsolutePath() + "）");
  }

  /** 本模块源码里真实需要的权限码。 */
  private static Set<String> requiredCodes() throws IOException {
    Set<String> codes = new LinkedHashSet<>();
    try (Stream<Path> walk = Files.walk(moduleSources())) {
      for (Path file :
          walk.filter(p -> p.toString().endsWith(".java")).filter(p -> !p.toString().endsWith("Models.java")).sorted().toList()) {
        String source = Files.readString(file);

        Set<String> actions = new LinkedHashSet<>();
        Matcher actionMatcher = ACTION.matcher(source);
        while (actionMatcher.find()) actions.add(actionMatcher.group(1));

        Matcher prefixMatcher = PREFIX.matcher(source);
        if (!actions.isEmpty()) {
          while (prefixMatcher.find()) {
            String raw = prefixMatcher.group(1);
            if (raw.endsWith(".")) {
              String family = raw.substring(0, raw.length() - 1);
              if (family.equals("crm")) {
                // 裸前缀："crm." + permission，而 permission 本身带族名（"review.read"）。
                for (String action : actions) {
                  int dot = action.indexOf('.');
                  if (dot > 0) {
                    codes.add(
                        "crm." + action.substring(0, dot) + "." + action.substring(dot + 1));
                  }
                }
              } else {
                for (String action : actions) {
                  if (!action.contains(".")) codes.add(family + "." + action);
                }
              }
            }
          }
        }

        // 字面量权限码：服务层的第二道校验（"crm.customers.pii.read"）走的是这条路。
        // ⚠️ 不能因为这个文件没有 call( 调用就跳过 —— 有的文件只做第二道校验。
        Matcher literalMatcher = LITERAL.matcher(source);
        while (literalMatcher.find()) {
          String literal = literalMatcher.group(1);
          if (!literal.endsWith(".") && literal.split("\\.").length >= 3) codes.add(literal);
        }
      }
    }
    return codes;
  }

  /** identity 迁移里登记过的 crm 权限码。 */
  private static Set<String> registeredCodes() throws IOException {
    Set<String> codes = new LinkedHashSet<>();
    try (Stream<Path> files = Files.list(identityMigrations())) {
      for (Path file : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
        String sql = Files.readString(file);
        for (String statement : sql.split(";")) {
          if (!statement.contains("INSERT INTO permission")) continue;

          // 形式 A：INSERT INTO permission SELECT 'crm.x.'||action, … FROM unnest(ARRAY[…])
          Matcher selectMatcher = SELECT_FORM.matcher(statement);
          while (selectMatcher.find()) {
            String prefix = selectMatcher.group(1);
            if (prefix.endsWith(".")) prefix = prefix.substring(0, prefix.length() - 1);
            Matcher arrayMatcher = ARRAY_FORM.matcher(statement);
            if (!arrayMatcher.find()) continue;
            for (String quoted : arrayMatcher.group(1).split(",")) {
              String action = quoted.replace("'", "").replace("::text[]", "").trim();
              if (!action.isEmpty()) codes.add(prefix + "." + action);
            }
          }

          // 形式 B：INSERT INTO permission(code, page_code, action) VALUES (…)
          Matcher valuesMatcher = VALUES_FORM.matcher(statement);
          while (valuesMatcher.find()) codes.add(valuesMatcher.group(1));
        }
      }
    }
    return codes;
  }

  @Test
  void crm用到的每个权限码都在identity登记过() throws IOException {
    Set<String> required = requiredCodes();
    Set<String> registered = registeredCodes();

    // 🔴 抽取本身要有效：一个码都没抽到时「全部已登记」也是空的真 —— 先钉住下限。
    assertThat(required)
        .as("从 crm 源码里没抽出任何权限码 —— 抽取逻辑失效了，本守卫等于没跑")
        .hasSizeGreaterThanOrEqualTo(40);

    List<String> missing = new ArrayList<>(required);
    missing.removeAll(registered);
    assertThat(missing)
        .as(
            "这些权限码在 crm 的端点里被 access.require 要着，"
                + "但 identity 的 permission 表里没登记 ⇒ role_permission 的外键让**任何角色都无法持有它们**"
                + " ⇒ 端点在真实部署里恒定 403")
        .isEmpty();
  }

  @Test
  void identity登记的crm权限码都是本模块真的用着的() throws IOException {
    Set<String> required = requiredCodes();
    Set<String> registered = registeredCodes();

    List<String> orphan = new ArrayList<>(registered);
    orphan.removeAll(required);
    assertThat(orphan)
        .as("identity 登记了 crm 端点并不需要的权限码 —— 多登记的码会让角色的权限清单出现幽灵条目")
        .isEmpty();
  }
}
