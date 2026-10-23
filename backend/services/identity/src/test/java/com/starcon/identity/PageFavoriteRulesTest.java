package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.starcon.identity.PageFavoriteModels.PageFavoriteItem;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.2.2.1 常用页面收藏 —— 载荷规则的**纯函数**单元测试。
 *
 * <p>这些规则不碰数据库，所以能逐条钉住：哪些形状被拒、排序怎么算。
 * 集成测试负责证明「真的落进 PostgreSQL、真的被 RLS 隔离」，
 * 两者不互相顶替。
 */
class PageFavoriteRulesTest {

  private static PageFavoriteItem item(String href, String pageCode) {
    return new PageFavoriteItem(href, "标题 " + href, "ops", pageCode, 0);
  }

  // ================================================================ 路径

  @Test
  void 合法的站内相对路径被接受() {
    assertThat(PageFavoriteRules.normalizeHref("/orders")).isEqualTo("/orders");
    assertThat(PageFavoriteRules.normalizeHref("  /orders  ")).isEqualTo("/orders");
    assertThat(PageFavoriteRules.normalizeHref("/orders/review")).isEqualTo("/orders/review");
    assertThat(PageFavoriteRules.normalizeHref("/orders?status=pending"))
        .isEqualTo("/orders?status=pending");
  }

  @Test
  void 外站地址被拒() {
    assertThrows(
        ResponseStatusException.class, () -> PageFavoriteRules.normalizeHref("https://evil.example"));
    assertThrows(
        ResponseStatusException.class, () -> PageFavoriteRules.normalizeHref("//evil.example"));
    assertThrows(ResponseStatusException.class, () -> PageFavoriteRules.normalizeHref("orders"));
  }

  @Test
  void 路径穿越与反斜杠被拒() {
    assertThrows(
        ResponseStatusException.class, () -> PageFavoriteRules.normalizeHref("/orders/../../etc"));
    assertThrows(
        ResponseStatusException.class, () -> PageFavoriteRules.normalizeHref("/orders\\review"));
  }

  @Test
  void 空白与控制字符被拒() {
    assertThrows(ResponseStatusException.class, () -> PageFavoriteRules.normalizeHref("/or ders"));
    assertThrows(
        ResponseStatusException.class, () -> PageFavoriteRules.normalizeHref("/or" + (char) 10 + "ders"));
  }

  // ================================================================ 标题 / 系统 / 页码

  @Test
  void 标题必须非空且长度受限() {
    assertThat(PageFavoriteRules.normalizeLabel("  订单中心 ")).isEqualTo("订单中心");
    assertThrows(ResponseStatusException.class, () -> PageFavoriteRules.normalizeLabel("   "));
    assertThrows(
        ResponseStatusException.class,
        () -> PageFavoriteRules.normalizeLabel("x".repeat(PageFavoriteRules.MAX_LABEL_LENGTH + 1)));
  }

  @Test
  void 所属系统必须在词表里() {
    assertThat(PageFavoriteRules.normalizeSystem("ops")).isEqualTo("ops");
    assertThat(PageFavoriteRules.normalizeSystem("top")).isEqualTo("top");
    assertThrows(ResponseStatusException.class, () -> PageFavoriteRules.normalizeSystem("nope"));
    assertThrows(ResponseStatusException.class, () -> PageFavoriteRules.normalizeSystem(""));
  }

  @Test
  void 页码可以留空但形状受控() {
    assertThat(PageFavoriteRules.normalizePageCode(null)).isEmpty();
    assertThat(PageFavoriteRules.normalizePageCode("  ")).isEmpty();
    assertThat(PageFavoriteRules.normalizePageCode("trade.orders")).isEqualTo("trade.orders");
    assertThrows(
        ResponseStatusException.class, () -> PageFavoriteRules.normalizePageCode("Trade.Orders"));
    assertThrows(
        ResponseStatusException.class, () -> PageFavoriteRules.normalizePageCode("-leading"));
  }

  // ================================================================ 载荷读写

  @Test
  void 载荷往返稳定() {
    List<PageFavoriteItem> items =
        List.of(
            new PageFavoriteItem("/orders", "订单中心", "ops", "trade.orders", 0),
            new PageFavoriteItem("/inventory", "库存中心", "storage", "warehouse.inventory", 1));
    String json = PageFavoriteRules.writeItems(items);
    assertThat(json).contains("\"items\"").contains("\"href\":\"/orders\"");
    List<PageFavoriteItem> back = PageFavoriteRules.readItems(json);
    assertThat(back).hasSize(2);
    assertThat(back.get(0).href()).isEqualTo("/orders");
    assertThat(back.get(0).pageCode()).isEqualTo("trade.orders");
    assertThat(back.get(1).order()).isEqualTo(1);
  }

  @Test
  void 读不出载荷时退回空清单而不是报错() {
    assertThat(PageFavoriteRules.readItems(null)).isEmpty();
    assertThat(PageFavoriteRules.readItems("")).isEmpty();
    assertThat(PageFavoriteRules.readItems("not json")).isEmpty();
    assertThat(PageFavoriteRules.readItems("[1,2,3]")).isEmpty();
    assertThat(PageFavoriteRules.readItems("{\"items\":\"nope\"}")).isEmpty();
  }

  @Test
  void 单条不合法被跳过其余照常读出() {
    String json =
        "{\"items\":["
            + "{\"href\":\"/orders\",\"label\":\"订单中心\",\"system\":\"ops\",\"pageCode\":\"trade.orders\"},"
            + "{\"href\":\"https://evil.example\",\"label\":\"坏路径\",\"system\":\"ops\"},"
            + "{\"href\":\"/inventory\",\"label\":\"库存中心\",\"system\":\"storage\"}"
            + "]}";
    List<PageFavoriteItem> items = PageFavoriteRules.readItems(json);
    assertThat(items).hasSize(2);
    assertThat(items.get(0).href()).isEqualTo("/orders");
    assertThat(items.get(1).href()).isEqualTo("/inventory");
  }

  @Test
  void 同一路径只留第一条() {
    String json =
        "{\"items\":["
            + "{\"href\":\"/orders\",\"label\":\"A\",\"system\":\"ops\"},"
            + "{\"href\":\"/orders\",\"label\":\"B\",\"system\":\"ops\"}"
            + "]}";
    List<PageFavoriteItem> items = PageFavoriteRules.readItems(json);
    assertThat(items).hasSize(1);
    assertThat(items.get(0).label()).isEqualTo("A");
  }

  // ================================================================ 排序

  @Test
  void 上移与下移() {
    List<PageFavoriteItem> all = List.of(item("/a", ""), item("/b", ""), item("/c", ""));
    Set<String> visible = Set.of("/a", "/b", "/c");

    List<PageFavoriteItem> up = PageFavoriteRules.move(all, visible, "/c", 1);
    assertThat(up.stream().map(PageFavoriteItem::href)).containsExactly("/a", "/c", "/b");

    List<PageFavoriteItem> down = PageFavoriteRules.move(all, visible, "/a", 2);
    assertThat(down.stream().map(PageFavoriteItem::href)).containsExactly("/b", "/c", "/a");
  }

  @Test
  void 越界的目标位被夹到两端() {
    List<PageFavoriteItem> all = List.of(item("/a", ""), item("/b", ""));
    Set<String> visible = Set.of("/a", "/b");

    List<PageFavoriteItem> past = PageFavoriteRules.move(all, visible, "/a", 99);
    assertThat(past.stream().map(PageFavoriteItem::href)).containsExactly("/b", "/a");

    List<PageFavoriteItem> negative = PageFavoriteRules.move(all, visible, "/b", -5);
    assertThat(negative.stream().map(PageFavoriteItem::href)).containsExactly("/b", "/a");
  }

  @Test
  void 隐藏项保持绝对位置不变只换可见顺序() {
    // 存储顺序：A(可见) H(隐藏) B(可见)
    List<PageFavoriteItem> all = List.of(item("/a", ""), item("/h", "x.page"), item("/b", ""));
    Set<String> visible = Set.of("/a", "/b");

    List<PageFavoriteItem> moved = PageFavoriteRules.move(all, visible, "/a", 1);
    assertThat(moved.stream().map(PageFavoriteItem::href)).containsExactly("/b", "/h", "/a");
    // 隐藏项仍在原来那个绝对位置（index 1）
    assertThat(moved.get(1).href()).isEqualTo("/h");
    assertThat(moved.get(1).pageCode()).isEqualTo("x.page");
  }

  @Test
  void 排序不改变条目本身的字段() {
    List<PageFavoriteItem> all = List.of(item("/a", "trade.orders"), item("/b", ""));
    Set<String> visible = Set.of("/a", "/b");
    List<PageFavoriteItem> moved = PageFavoriteRules.move(all, visible, "/a", 1);
    assertThat(moved.get(1).label()).isEqualTo("标题 /a");
    assertThat(moved.get(1).pageCode()).isEqualTo("trade.orders");
  }

  @Test
  void 排序不存在的收藏被拒() {
    List<PageFavoriteItem> all = List.of(item("/a", ""));
    assertThrows(
        ResponseStatusException.class,
        () -> PageFavoriteRules.move(all, Set.of("/a"), "/zzz", 0));
  }

  @Test
  void 排序当前不可见的收藏被拒() {
    List<PageFavoriteItem> all = List.of(item("/a", ""), item("/h", "x.page"));
    assertThrows(
        ResponseStatusException.class,
        () -> PageFavoriteRules.move(all, Set.of("/a"), "/h", 0));
  }
}
