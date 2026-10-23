package com.starcon.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.2.2.1 常用页面收藏 —— 载荷的**纯函数**校验与排序规则。
 *
 * <p>刻意做成不碰数据库的纯函数：这些规则要能被单元测试逐条钉住
 * （哪些形状被拒、排序怎么算），而不是只能靠集成测试间接观察。
 *
 * <p><b>存储形状</b>：复用 1.4.1.2 建好的 {@code user_preference} 表，
 * 用本模块<b>自己的</b> {@code page_key = dashboard.favorites}，载荷是一个 JSONB：
 *
 * <pre>{@code
 * {"items":[{"href":"/orders","label":"订单中心","system":"ops","pageCode":"trade.orders","order":0}]}
 * }</pre>
 *
 * <p><b>为什么排序位存在载荷里而不是新建一列</b>：{@code user_preference} 是
 * 「一页一行 + JSONB 载荷」的形状（V336 已定死），同批还有 1.4.1.1 / 1.4.2.1
 * 两条车道在用这张表 —— 加一个没有 DEFAULT 的列会让对方的 INSERT 当场违约。
 * 拖拽排序用<b>数组顺序</b>表达，{@code order} 只是数组下标的冗余副本（读时以数组顺序为准）。
 *
 * <p><b>为什么不复用 {@link UserPreferencePayloadRules}</b>：那套规则守的是
 * 「任意键 → 任意值」的通用偏好载荷；本模块的载荷是**定长数组**（每项四个固定字段），
 * 规则完全不同（要拒非法 href、非法系统枚举、重复 href、超长列表）。
 * 而且 1.4.1.2 是**同批并行车道**，它的文件随时可能变 ——
 * 本车道编译期依赖它，会把「两条车道改同一个文件」变成必然的合并冲突。
 */
public final class PageFavoriteRules {

  private PageFavoriteRules() {}

  /** 本模块在 {@code user_preference} 里占的那一行。 */
  public static final String PAGE_KEY = "dashboard.favorites";

  /** 收藏条数上限。个人快捷区不是仓库；64 条已经远超「高频页面」的语义。 */
  public static final int MAX_ITEMS = 64;

  /** 标题长度上限（与 1.2.2.2 的入口标题一致）。 */
  public static final int MAX_LABEL_LENGTH = 100;

  /** 路径长度上限。 */
  public static final int MAX_HREF_LENGTH = 200;

  /** 权限页码词表：小写字母 / 数字 / 点 / 下划线 / 连字符，1..64。 */
  public static final Pattern PAGE_CODE = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

  /**
   * 所属系统词表。
   *
   * <p>{@code dev} / {@code ops} / {@code logistics} / {@code storage} 取自前端页面注册表的
   * {@code SystemId}；{@code top} 是**顶层固定项**（工作台 / 渠道与店铺 / AI Copilot）——
   * 它们不属于任何职能系统（见 nav-config 的说明），但仍是可收藏的页面，
   * 所以给它们一个显式的归属而不是留空。
   */
  public static final Set<String> SYSTEMS =
      Set.of("dev", "ops", "logistics", "storage", "top");

  private static final ObjectMapper JSON = new ObjectMapper();

  // ================================================================ 入参校验

  /**
   * 校验并归一化一条收藏入参。
   *
   * @throws ResponseStatusException 形状不合法时（400，带具体原因）
   */
  public static PageFavoriteModels.PageFavoriteItem validate(
      PageFavoriteModels.PageFavoriteInput input) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏内容不能为空");
    }
    String href = normalizeHref(input.href());
    String label = normalizeLabel(input.label());
    String system = normalizeSystem(input.system());
    String pageCode = normalizePageCode(input.pageCode());
    return new PageFavoriteModels.PageFavoriteItem(href, label, system, pageCode, 0);
  }

  /**
   * 路径归一化：必须是**站内相对路径**。
   *
   * <p>刻意拒掉的东西（每一条都有一个具体的攻击/故障形状）：
   *
   * <ul>
   *   <li><b>不以 {@code /} 开头</b> —— {@code https://evil.example} 不是站内路径，
   *       点了就是把用户送出系统（钓鱼）；</li>
   *   <li><b>以 {@code //} 开头</b> —— 协议相对 URL（{@code //evil.example}），
   *       浏览器会当成外站地址；</li>
   *   <li><b>含 {@code ..}</b> —— 路径穿越，规范化之后可能跳出预期的页面；</li>
   *   <li><b>含反斜杠或控制字符/空白</b> —— 反斜杠在部分代理与浏览器里被规范化成 {@code /}，
   *       控制字符能让日志与界面串行错乱；</li>
   *   <li><b>超长</b> —— 界面上显示不下，日志里也读不了。</li>
   * </ul>
   */
  public static String normalizeHref(String raw) {
    String href = raw == null ? "" : raw.trim();
    if (href.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏路径不能为空");
    }
    if (!href.startsWith("/")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "收藏路径必须是站内相对路径（以 / 开头）");
    }
    if (href.startsWith("//")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "收藏路径不能是协议相对地址（不能以 // 开头）");
    }
    if (href.contains("..")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏路径不能包含 ..");
    }
    if (href.contains("\\")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏路径不能包含反斜杠");
    }
    for (int i = 0; i < href.length(); i++) {
      char c = href.charAt(i);
      if (c <= ' ' || c == 0x7f) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏路径不能包含空白或控制字符");
      }
    }
    if (href.length() > MAX_HREF_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "收藏路径长度不能超过 " + MAX_HREF_LENGTH + " 个字符");
    }
    return href;
  }

  /** 标题归一化：非空、长度受限。 */
  public static String normalizeLabel(String raw) {
    String label = raw == null ? "" : raw.trim();
    if (label.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏标题不能为空");
    }
    if (label.length() > MAX_LABEL_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "收藏标题长度不能超过 " + MAX_LABEL_LENGTH + " 个字符");
    }
    for (int i = 0; i < label.length(); i++) {
      if (label.charAt(i) < 0x20) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏标题不能包含控制字符");
      }
    }
    return label;
  }

  /** 所属系统归一化：必须是词表里的五个之一。 */
  public static String normalizeSystem(String raw) {
    String system = raw == null ? "" : raw.trim();
    if (!SYSTEMS.contains(system)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "所属系统只能是 " + String.join(" / ", SYSTEMS));
    }
    return system;
  }

  /**
   * 权限页码归一化：可以留空。
   *
   * <p>留空 = 该页面**没有登记页面级读权限**，因此不受权限闸约束（详见
   * {@link PageFavoriteService} 的权限闸说明）。
   */
  public static String normalizePageCode(String raw) {
    String pageCode = raw == null ? "" : raw.trim();
    if (pageCode.isEmpty()) return "";
    if (!PAGE_CODE.matcher(pageCode).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "目标页面码只能是小写字母、数字与 . _ -，且必须以字母或数字开头");
    }
    return pageCode;
  }

  // ================================================================ 载荷读写

  /**
   * 把数据库里的 jsonb 文本读回收藏清单。
   *
   * <p><b>读不出来时退回空清单，而不是报错</b>：老行不该让页面崩。
   * 逐条跳过不合法项（而不是整份丢弃）—— 少一条收藏好过整个收藏区打不开。
   */
  public static List<PageFavoriteModels.PageFavoriteItem> readItems(String json) {
    if (json == null || json.isBlank()) return List.of();
    Map<String, Object> payload;
    try {
      payload = JSON.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      return List.of();
    }
    if (payload == null) return List.of();
    Object rawItems = payload.get("items");
    if (!(rawItems instanceof List<?> list)) return List.of();
    List<PageFavoriteModels.PageFavoriteItem> items = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Object raw : list) {
      if (!(raw instanceof Map<?, ?> map)) continue;
      try {
        PageFavoriteModels.PageFavoriteItem item =
            new PageFavoriteModels.PageFavoriteItem(
                normalizeHref(asString(map.get("href"))),
                normalizeLabel(asString(map.get("label"))),
                normalizeSystem(asString(map.get("system"))),
                normalizePageCode(asString(map.get("pageCode"))),
                items.size());
        if (!seen.add(item.href())) continue; // 同一路径只留第一条
        items.add(item);
      } catch (ResponseStatusException ignored) {
        // 单条不合法就跳过这一条，不影响其余收藏。
      }
    }
    return items;
  }

  /** 把收藏清单序列化成 jsonb 文本。 */
  public static String writeItems(List<PageFavoriteModels.PageFavoriteItem> items) {
    List<Map<String, Object>> payload = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      PageFavoriteModels.PageFavoriteItem item = items.get(i);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("href", item.href());
      entry.put("label", item.label());
      entry.put("system", item.system());
      entry.put("pageCode", item.pageCode());
      entry.put("order", i);
      payload.add(entry);
    }
    try {
      return JSON.writeValueAsString(Map.of("items", payload));
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏清单无法序列化");
    }
  }

  // ================================================================ 排序

  /**
   * 把一条收藏移到**可见清单**的第 {@code toIndex} 位。
   *
   * <p><b>为什么可见清单的下标和存储清单的下标不是一回事</b>：无权限的收藏被自动隐藏，
   * 但它仍然占着存储清单里的位置。所以「移到第 N 位」必须按**可见**的 N 来理解，
   * 隐藏项在存储清单里保持原位（绝对位置不变）。
   *
   * <p><b>算法</b>：
   *
   * <ol>
   *   <li>取出被移动项，把它从可见序列里摘掉；</li>
   *   <li>插到可见序列的第 {@code toIndex} 位（越界则夹到两端）；</li>
   *   <li>回填：存储清单里**可见项占的那些绝对槽位**按新的可见顺序依次填，
   *       隐藏项保持原绝对位置不变。</li>
   * </ol>
   *
   * <p>纯函数：同样的入参永远给同样的结果（可单元测试，不依赖数据库）。
   *
   * @param all 存储清单（完整顺序，含隐藏项）
   * @param visibleHrefs 当前可见的 href 集合
   * @param href 被移动的收藏的路径
   * @param toIndex 目标位（可见清单里的下标）
   * @throws ResponseStatusException 找不到这一条 / 这一条当前不可见时（400）
   */
  public static List<PageFavoriteModels.PageFavoriteItem> move(
      List<PageFavoriteModels.PageFavoriteItem> all,
      Set<String> visibleHrefs,
      String href,
      int toIndex) {
    String target = href == null ? "" : href.trim();
    PageFavoriteModels.PageFavoriteItem moved = null;
    for (PageFavoriteModels.PageFavoriteItem item : all) {
      if (item.href().equals(target)) {
        moved = item;
        break;
      }
    }
    if (moved == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏不存在：" + target);
    }
    if (!visibleHrefs.contains(moved.href())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "该收藏当前因权限不足被隐藏，不能排序");
    }

    // ① 可见序列（去掉被移动项）
    List<PageFavoriteModels.PageFavoriteItem> visibleRest = new ArrayList<>();
    for (PageFavoriteModels.PageFavoriteItem item : all) {
      if (item.href().equals(target)) continue;
      if (visibleHrefs.contains(item.href())) visibleRest.add(item);
    }
    // ② 插到目标位（夹紧）
    int target2 = Math.max(0, Math.min(toIndex, visibleRest.size()));
    List<PageFavoriteModels.PageFavoriteItem> visibleNew = new ArrayList<>(visibleRest);
    visibleNew.add(target2, moved);

    // ③ 回填：可见槽位按新顺序填，隐藏项保持绝对位置
    List<PageFavoriteModels.PageFavoriteItem> out = new ArrayList<>(all.size());
    int cursor = 0;
    for (PageFavoriteModels.PageFavoriteItem item : all) {
      if (visibleHrefs.contains(item.href())) {
        if (cursor >= visibleNew.size()) {
          // 理论上不可达（可见项数与槽位数一致）；兜底也不丢项。
          out.add(item);
        } else {
          out.add(visibleNew.get(cursor++));
        }
      } else {
        out.add(item);
      }
    }
    return out;
  }

  private static String asString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
