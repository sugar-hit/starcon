package com.starcon.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * trade 测试**不得**回落到机器全局上游地址 —— 6.2.1.2 批次 L2 红的结构性防线。
 *
 * <p><b>这条防线钉的是什么缺陷</b>：2026-10-22 批次 L2（backend 段 {@code mvnw verify}）
 * 在 trade 模块红了三条租户断言，报 503「商品主数据服务返回 404」。同一次缺陷单独跑绿、
 * 与同门类一起跑绿、整模块连跑才红 ⇒ 是**顺序/环境依赖**，不是确定性缺陷。
 *
 * <p><b>结构性的那一份</b>：主代码里 CATALOG_URL / WAREHOUSE_URL / CHANNEL_URL 的默认值是
 * {@code http://127.0.0.1:8082 / :8084 / :8083}，而这三个端口正是
 * {@code deploy/docker/docker-compose.catalog.yml} 映射到宿主机的**真实服务端口**
 * （catalog / channel / warehouse）。⇒ 任何一个**忘了显式给上游地址**的测试类，
 * 都会去敲**这台机器上碰巧在跑的那台服务**。测试结果于是取决于「跑那一刻机器上跑着什么」，
 * 而不是取决于被测代码 —— 这正是「静默依赖机器瞬时状态」的温床。
 *
 * <p><b>本用例断言三件事</b>：
 *
 * <ol>
 *   <li>主代码里**确实存在**机器全局上游默认值（否则本用例会空转 ⇒ 必须先自证扫描有效）；</li>
 *   <li>测试类路径上的兜底值**覆盖了每一个**这样的键，且**不等于**那个机器全局地址
 *       （⇒ 显式覆盖优先，忘了给就落不到真服务上）；</li>
 *   <li>兜底值**真的连不通**（不是「假定连不通」而是**当场验证连不通**），
 *       且它的端口**不在**主代码用到的机器全局端口集合里。</li>
 * </ol>
 *
 * <p>这些断言只读源码与 classpath，**不启 Spring 容器、不连数据库**，跑起来是毫秒级的。
 */
class TradeUpstreamAddressIsolationTest {

  /** 主代码里 {@code @Value("${KEY:DEFAULT}")} 的形状。 */
  private static final Pattern VALUE_DEFAULT = Pattern.compile("\"\\$\\{([A-Z0-9_]+):([^}]*)\\}\"");

  /** 「机器全局地址」：指向本机某个固定端口的 http URL。 */
  private static final Pattern MACHINE_GLOBAL =
      Pattern.compile("^http://(127\\.0\\.0\\.1|localhost)(:(\\d+))?/?$");

  /** 兜底值文件（测试类路径）。 */
  private static final String FALLBACK_RESOURCE = "application.properties";

  // ================================================================ 工具

  /** trade 主源码目录（从当前工作目录往上找，兼容从模块目录或仓库根启动）。 */
  static Path mainSources() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      Path candidate = dir.resolve("src/main/java/com/starcon/trade");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("找不到 trade 主源码目录 src/main/java/com/starcon/trade");
  }

  /** 键 → 主代码里那些「机器全局」的默认值。 */
  static Map<String, List<String>> machineGlobalDefaults() throws IOException {
    Map<String, List<String>> found = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(mainSources())) {
      for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        Matcher m = VALUE_DEFAULT.matcher(source);
        while (m.find()) {
          String key = m.group(1);
          String value = m.group(2);
          if (MACHINE_GLOBAL.matcher(value).matches()) {
            found.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(value);
          }
        }
      }
    }
    return found;
  }

  /** 测试类路径上的兜底值。 */
  static Properties fallbacks() throws IOException {
    Properties props = new Properties();
    InputStream in =
        TradeUpstreamAddressIsolationTest.class
            .getClassLoader()
            .getResourceAsStream(FALLBACK_RESOURCE);
    assertThat(in).as("测试类路径上必须有 %s（兜底值文件）", FALLBACK_RESOURCE).isNotNull();
    try (in) {
      props.load(in);
    }
    return props;
  }

  /** 兜底值里解析出的端口。 */
  static int portOf(String url) {
    URI uri = URI.create(url);
    assertThat(uri.getPort()).as("兜底地址必须带显式端口：%s", url).isNotNegative();
    return uri.getPort();
  }

  // ================================================================ 断言

  @Test
  void 主代码里确实存在机器全局上游默认值否则本用例空转() throws IOException {
    var defaults = machineGlobalDefaults();

    assertThat(defaults)
        .as("本用例的前提：主代码里确实有「上游地址默认值 = 机器全局固定端口」的写法")
        .as("键 → 主代码里的机器全局默认值")
        .isNotEmpty();
    assertThat(defaults.keySet())
        .as("已知的上游键（CATALOG / WAREHOUSE / CHANNEL）")
        .contains("CATALOG_URL", "WAREHOUSE_URL", "CHANNEL_URL");
  }

  @Test
  void 测试的兜底上游地址必须覆盖每一个机器全局默认值() throws IOException {
    var defaults = machineGlobalDefaults();
    var fallbacks = fallbacks();

    for (var entry : defaults.entrySet()) {
      String key = entry.getKey();
      String fallback = fallbacks.getProperty(key);

      assertThat(fallback)
          .as("%s 在主代码里默认指向机器全局地址 %s；测试必须显式覆盖它", key, entry.getValue())
          .as("否则「忘了给上游地址」的测试类会静默敲到这台机器上的真服务")
          .isNotNull();
      for (String machineGlobal : entry.getValue()) {
        assertThat(fallback)
            .as("%s 的测试兜底值不得等于机器全局地址 %s", key, machineGlobal)
            .isNotEqualTo(machineGlobal);
      }
    }
  }

  @Test
  void 测试的兜底上游地址必须真的连不通() throws IOException {
    var defaults = machineGlobalDefaults();
    var fallbacks = fallbacks();

    for (String key : defaults.keySet()) {
      String fallback = fallbacks.getProperty(key);
      int port = portOf(fallback);

      assertThat(port).as("%s 的兜底端口不得与主代码用到的机器全局端口相同", key).isNotIn(machineGlobalPorts(defaults));

      // 不是「假定它连不通」，是当场连一次确认连不通（端口 1 = tcpmux，无人监听）。
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
        assertThat(socket.isConnected())
            .as("%s 的兜底地址 %s 竟然连上了 —— 兜底值必须是「必然连不通」的", key, fallback)
            .isFalse();
      } catch (IOException expected) {
        // 连不上正是我们要的：兜底值**确定**不可达（端口 1 = tcpmux，无人监听）。
      }
    }
  }

  /** 主代码用到的机器全局端口集合。 */
  static List<Integer> machineGlobalPorts(Map<String, List<String>> defaults) {
    return defaults.values().stream()
        .flatMap(List::stream)
        .map(MACHINE_GLOBAL::matcher)
        .filter(Matcher::matches)
        .filter(m -> m.group(3) != null)
        .map(m -> Integer.parseInt(m.group(3)))
        .distinct()
        .toList();
  }
}
