package com.starcon.identity;

import static com.starcon.identity.GlobalSearchRecentModels.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.2.1.1 全局搜索框 —— 「最近搜索记录」的读写。
 *
 * <p><b>这是纯个人偏好数据</b>：它不是业务事实，而是「这个人最近搜过什么」。
 * 所以它的写入所有权在本服务（identity，与 1.4 个人设置同域），不在任何业务服务；
 * 业务服务那边只读自己的业务表。
 *
 * <p><b>租户来自已认证会话，user_id 也来自已认证会话</b>：两个都不是请求参数，
 * 更不是请求头。伪造 {@code X-Tenant-Id} 头改不动 {@code app.current_tenant}，
 * 也拿不到别人的 user_id。
 *
 * <p><b>「只看自己的」在两层保证</b>：RLS 保证「本租户」，SQL 里的 {@code user_id = ?}
 * 保证「本租户里的这个人」。少任何一层都会变成「能看同事的搜索记录」。
 */
@Service
public class GlobalSearchRecentService {

  /** 蓝图写死的最小关键词长度：少于 2 个字符不记录（与检索端点同一口径）。 */
  public static final int MIN_KEYWORD_LENGTH = 2;

  private static final int MAX_LIMIT = 50;

  /** 允许的域。空串表示「只搜了没选中」。 */
  private static final List<String> DOMAINS =
      List.of(
          "", "PRODUCT", "ORDER", "PURCHASE", "SHOP", "SUPPLIER", "LOGISTICS", "CUSTOMER", "WAREHOUSE");

  private final DSLContext sql;

  public GlobalSearchRecentService(DSLContext sql) {
    this.sql = sql;
  }

  /** 读这个人的最近搜索（新的在前）。 */
  public GlobalSearchRecentList list(UUID tenantId, UUID userId, int limit) {
    int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
    List<GlobalSearchRecentEntry> entries = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select keyword, domain, primary_ref, searched_at"
                + " from global_search_recent"
                + " where tenant_id = ?::uuid and user_id = ?::uuid"
                + " order by searched_at desc, keyword"
                + " limit ?",
            tenantId,
            userId,
            size)) {
      entries.add(
          new GlobalSearchRecentEntry(
              r.get("keyword", String.class),
              blankToNull(r.get("domain", String.class)),
              blankToNull(r.get("primary_ref", String.class)),
              r.get("searched_at", OffsetDateTime.class)));
    }
    return new GlobalSearchRecentList(entries, size);
  }

  /**
   * 登记一条最近搜索（同一个关键词重复登记只刷新时刻，不新增行）。
   *
   * <p>刻意<b>不</b>记录任何业务标识以外的敏感内容：只存关键词、域与主标识，
   * 不存命中的那条记录的其它字段 —— 最近搜索是个人偏好，不是业务事实的副本。
   */
  public GlobalSearchRecentEntry record(
      UUID tenantId, UUID userId, GlobalSearchRecentInput input) {
    String keyword = normalize(input == null ? null : input.keyword());
    String domain = normalizeDomain(input == null ? null : input.domain());
    String primaryRef = input == null || input.primaryRef() == null ? "" : input.primaryRef().trim();
    if (primaryRef.length() > 200) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "主标识长度不能超过 200 个字符");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.fetch(
        "insert into global_search_recent(id,tenant_id,user_id,keyword,domain,primary_ref,searched_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::timestamptz)"
            + " on conflict (tenant_id,user_id,keyword) do update"
            + " set domain = excluded.domain, primary_ref = excluded.primary_ref,"
            + "     searched_at = excluded.searched_at",
        UUID.randomUUID(),
        tenantId,
        userId,
        keyword,
        domain,
        primaryRef,
        now);
    return new GlobalSearchRecentEntry(keyword, blankToNull(domain), blankToNull(primaryRef), now);
  }

  /** 清空这个人的最近搜索。 */
  public void clear(UUID tenantId, UUID userId) {
    sql.fetch(
        "delete from global_search_recent where tenant_id = ?::uuid and user_id = ?::uuid",
        tenantId,
        userId);
  }

  /** 关键词归一化：去首尾空白；少于 2 个字符直接 400。 */
  static String normalize(String rawKeyword) {
    String keyword = rawKeyword == null ? "" : rawKeyword.trim();
    if (keyword.length() < MIN_KEYWORD_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "搜索关键词至少需要 " + MIN_KEYWORD_LENGTH + " 个字符");
    }
    if (keyword.length() > 200) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索关键词长度不能超过 200 个字符");
    }
    return keyword;
  }

  /** 域归一化：不在词表里就是非法值（不静默改成空串）。 */
  static String normalizeDomain(String rawDomain) {
    String domain = rawDomain == null ? "" : rawDomain.trim();
    if (!DOMAINS.contains(domain)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的检索域：" + domain);
    }
    return domain;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
