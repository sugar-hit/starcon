package com.starcon.identity;

import static com.starcon.identity.PageFavoriteModels.PageFavoriteInput;
import static com.starcon.identity.PageFavoriteModels.PageFavoriteItem;
import static com.starcon.identity.PageFavoriteModels.PageFavoriteMove;
import static com.starcon.identity.PageFavoriteModels.PageFavoriteSet;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.2.2.1 常用页面收藏 —— 「个人收藏清单」的读写。
 *
 * <p><b>这是纯个人配置</b>：它不是业务事实，而是「我高频用哪几个页面、按什么顺序」。
 * 存储落在 1.4.1.2 建好的 {@code user_preference} 表里，用本模块<b>自己的</b>
 * {@code page_key = dashboard.favorites}（V336 的形状：一页一行 + JSONB 载荷，
 * 第二次写入是覆盖）。<b>本模块不建表、不 ALTER 那张表</b> ——
 * 同批还有 1.4.1.1 / 1.4.2.1 两条车道在用那张表。
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：两个都不是请求参数，更不是请求头。
 * 伪造 {@code X-Tenant-Id} 头改不动 {@code app.current_tenant}，也拿不到别人的 membership_id。
 *
 * <p><b>「只看自己的」在两层保证</b>：RLS 保证「本租户」，SQL 里的 {@code membership_id = ?}
 * 保证「本租户里的这个人」。少任何一层都会变成「能看同事收藏了哪些页面」。
 *
 * <p><b>权限闸（蓝图的硬约束：无权限的收藏自动隐藏）</b>：
 * 一条收藏带着目标页面的权限页码 {@code pageCode}；
 * 当 {@code <pageCode>.read} 不在我的权限里时，这条收藏<b>不返回</b>
 * （不是返回后再置灰 —— 与 1.2.2.2 的权限闸同一纪律）。
 *
 * <ul>
 *   <li><b>隐藏不等于删除</b>：权限是临时收回的，隐藏的那条仍然留在存储里，
 *       权限恢复后自动回来；</li>
 *   <li><b>所以要能清掉它们</b>：{@link #removeHidden} 把当前因权限不足而隐藏的收藏删掉
 *       （否则用户永远看不到、也永远删不掉一条幽灵收藏）；</li>
 *   <li><b>没有页面级读权限的页面不受闸</b>：{@code pageCode} 留空即表示
 *       「这一页在权限目录里没有 read 权限」—— 没有闸就没有隐藏。</li>
 * </ul>
 *
 * <p><b>为什么用 {@code ?::timestamptz} 而不是 {@code Timestamp.valueOf(...)}</b>：
 * 后者按 JVM 默认时区解释 {@code LocalDateTime}，在东八区会静默偏 −28800s。
 * 这里统一用 {@link OffsetDateTime} 绑定。
 */
@Service
public class PageFavoriteService {

  private final DSLContext sql;

  public PageFavoriteService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 读

  /**
   * 读我的收藏清单（已按顺序排好，无权限的自动隐藏）。
   *
   * <p><b>没收藏过时返回空清单而不是 404</b>：「还没收藏过任何页面」是常态，不是异常。
   */
  public PageFavoriteSet read(UUID tenantId, UUID membershipId, Set<String> permissions) {
    return view(load(tenantId, membershipId), permissions);
  }

  // ================================================================ 写

  /**
   * 收藏一个页面。
   *
   * <p><b>同一个 {@code href} 重复收藏是覆盖，不是新增</b>：清单里不会同时出现两条同一个页面。
   * 覆盖时**保留原来的位置**（用户排好的顺序不该被一次重复点击打乱）。
   */
  public PageFavoriteSet add(
      UUID tenantId, UUID membershipId, Set<String> permissions, PageFavoriteInput input) {
    PageFavoriteItem item = PageFavoriteRules.validate(input);
    if (!item.pageCode().isEmpty()) {
      requireRegisteredPage(item.pageCode());
    }
    List<PageFavoriteItem> items = new ArrayList<>(load(tenantId, membershipId));
    int existing = -1;
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).href().equals(item.href())) {
        existing = i;
        break;
      }
    }
    if (existing >= 0) {
      items.set(existing, item);
    } else {
      if (items.size() >= PageFavoriteRules.MAX_ITEMS) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "收藏条数已达上限（" + PageFavoriteRules.MAX_ITEMS + " 条）");
      }
      items.add(item);
    }
    store(tenantId, membershipId, items);
    return view(items, permissions);
  }

  /**
   * 取消收藏。
   *
   * <p><b>幂等</b>：本来就没收藏过这一页时也算成功 —— 「取消收藏」不是一个需要区分
   * 「本来就没有」的动作，界面上点一下 X 就该少一条，不该因为重复点击报错。
   */
  public PageFavoriteSet remove(
      UUID tenantId, UUID membershipId, Set<String> permissions, String rawHref) {
    String href = PageFavoriteRules.normalizeHref(rawHref);
    List<PageFavoriteItem> items = new ArrayList<>();
    for (PageFavoriteItem item : load(tenantId, membershipId)) {
      if (!item.href().equals(href)) items.add(item);
    }
    store(tenantId, membershipId, items);
    return view(items, permissions);
  }

  /**
   * 排序：把一条收藏移到**可见清单**的第 {@code toIndex} 位。
   *
   * <p>拖拽排序与「上移/下移」按钮在本模块里是**同一个动作**（同一个端点、同一套语义），
   * 差别只在前端怎么算出 {@code toIndex}。
   */
  public PageFavoriteSet move(
      UUID tenantId, UUID membershipId, Set<String> permissions, PageFavoriteMove input) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序内容不能为空");
    }
    List<PageFavoriteItem> items = load(tenantId, membershipId);
    Set<String> visible = new LinkedHashSet<>();
    for (PageFavoriteItem item : items) {
      if (isVisible(item, permissions)) visible.add(item.href());
    }
    List<PageFavoriteItem> moved =
        PageFavoriteRules.move(items, visible, input.href(), input.toIndex());
    store(tenantId, membershipId, moved);
    return view(moved, permissions);
  }

  /**
   * 清掉当前因权限不足而隐藏的收藏。
   *
   * <p>存在的理由：权限闸把无权访问的收藏藏起来，但用户**看不见也就删不掉**，
   * 于是会攒下一堆幽灵收藏（自己都不知道什么时候点的）。
   * 这个动作让「看不见的那些」可以被一次性清干净。
   */
  public PageFavoriteSet removeHidden(
      UUID tenantId, UUID membershipId, Set<String> permissions) {
    List<PageFavoriteItem> items = new ArrayList<>();
    for (PageFavoriteItem item : load(tenantId, membershipId)) {
      if (isVisible(item, permissions)) items.add(item);
    }
    store(tenantId, membershipId, items);
    return view(items, permissions);
  }

  // ================================================================ 内部

  /** 读存储清单（完整顺序，含被权限闸隐藏的条目）。 */
  private List<PageFavoriteItem> load(UUID tenantId, UUID membershipId) {
    Record row =
        sql.fetchOne(
            "select preference::text as pref from user_preference"
                + " where tenant_id = ?::uuid and membership_id = ?::uuid and page_key = ?::varchar",
            tenantId,
            membershipId,
            PageFavoriteRules.PAGE_KEY);
    if (row == null) return List.of();
    return PageFavoriteRules.readItems(row.get("pref", String.class));
  }

  /** 写存储清单（**upsert**：同一 page_key 覆盖，不新增行）。 */
  private void store(UUID tenantId, UUID membershipId, List<PageFavoriteItem> items) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into user_preference"
            + "(id,tenant_id,membership_id,page_key,preference,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::jsonb,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id,membership_id,page_key) do update"
            + " set preference = excluded.preference, updated_at = excluded.updated_at",
        UUID.randomUUID(),
        tenantId,
        membershipId,
        PageFavoriteRules.PAGE_KEY,
        PageFavoriteRules.writeItems(items),
        now,
        now);
  }

  /** 组装返回视图：可见条目（重新编号）+ 隐藏条数 + 总条数。 */
  private PageFavoriteSet view(List<PageFavoriteItem> items, Set<String> permissions) {
    List<PageFavoriteItem> visible = new ArrayList<>();
    int hidden = 0;
    for (PageFavoriteItem item : items) {
      if (isVisible(item, permissions)) {
        visible.add(new PageFavoriteItem(item.href(), item.label(), item.system(), item.pageCode(), visible.size()));
      } else {
        hidden++;
      }
    }
    return new PageFavoriteSet(visible, hidden, items.size());
  }

  /**
   * 权限闸：这条收藏现在能不能看见。
   *
   * <p>{@code pageCode} 为空 = 该页在权限目录里没有 read 权限 ⇒ 没有闸 ⇒ 可见。
   * 否则 {@code <pageCode>.read} 不在我的权限里就隐藏。
   */
  private boolean isVisible(PageFavoriteItem item, Set<String> permissions) {
    if (item.pageCode().isEmpty()) return true;
    return permissions.contains(item.pageCode() + ".read");
  }

  /** 目标页面必须真的登记过 {@code <pageCode>.read}（与 1.2.2.2 的 pageIsRegistered 同纪律）。 */
  private void requireRegisteredPage(String pageCode) {
    if (sql.fetch("select 1 from permission where code = ?::varchar", pageCode + ".read").isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标页面未注册：" + pageCode);
    }
  }
}
