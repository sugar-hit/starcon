package com.starcon.identity;

import static com.starcon.identity.NotificationInboxModels.NotificationReadEntry;
import static com.starcon.identity.NotificationInboxModels.NotificationReadResult;
import static com.starcon.identity.NotificationInboxModels.NotificationReadState;
import static com.starcon.identity.NotificationInboxModels.NotificationSubscriptions;
import static com.starcon.identity.NotificationInboxRules.NotificationKey;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 1.3.1.1 通知列表 —— 「通知收件箱个人状态」的读写。
 *
 * <p><b>这里只有个人状态，没有通知内容</b>：通知内容是各域既有事件的只读投影，
 * 它们住在各自的库里（warehouse / trade / channel / finance / crm），
 * 按模块边界纪律只能通过它们的**授权只读端点**读，本服务不碰那些库。
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：两个都不是请求参数，更不是请求头。
 * 伪造 {@code X-Tenant-Id} 头改不动 {@code app.current_tenant}，也拿不到别人的 membership_id。
 *
 * <p><b>「只看自己的」在两层保证</b>：RLS 保证「本租户」，SQL 里的 {@code membership_id = ?}
 * 保证「本租户里的这个人」。少任何一层都会变成「能看同事读过哪些通知」。
 *
 * <p><b>为什么用 {@code ?::timestamptz} 而不是 {@code Timestamp.valueOf(...)}</b>：
 * 后者按 JVM 默认时区解释 {@code LocalDateTime}，在东八区会静默偏 −28800s。
 * 这里统一用 {@link OffsetDateTime} 绑定。
 */
@Service
public class NotificationInboxService {

  private final DSLContext sql;

  public NotificationInboxService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 已读状态

  /**
   * 查一批通知的已读状态。
   *
   * <p><b>只返回已读的那些</b>：不在结果里 = 未读。返回体因此与「这批键」的规模成比例，
   * 而不是与「我历史上读过多少条」成比例。
   */
  public NotificationReadState readState(
      UUID tenantId, UUID membershipId, List<NotificationKey> keys) {
    if (keys.isEmpty()) return new NotificationReadState(List.of());
    List<Object> bindings = new ArrayList<>();
    bindings.add(tenantId);
    bindings.add(membershipId);
    String tuples = tuplePlaceholders(keys.size());
    for (NotificationKey key : keys) {
      bindings.add(key.type());
      bindings.add(key.ref());
    }
    List<NotificationReadEntry> read = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select notification_type, notification_ref, read_at from notification_read_state"
                + " where tenant_id = ?::uuid and membership_id = ?::uuid"
                + " and (notification_type, notification_ref) in ("
                + tuples
                + ")",
            bindings.toArray())) {
      read.add(toEntry(row));
    }
    return new NotificationReadState(read);
  }

  /**
   * 标记已读（**批量**：只对**点名**的这些键生效）。
   *
   * <p>重复标记同一键是**覆盖**（刷新读到的时刻），不新增行 ——
   * 「读了又读一遍」不该在收件箱里堆出两条。
   */
  public NotificationReadResult markRead(
      UUID tenantId, UUID membershipId, List<NotificationKey> keys) {
    if (keys.isEmpty()) return new NotificationReadResult(List.of(), 0);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<Object> bindings = new ArrayList<>();
    StringBuilder values = new StringBuilder();
    for (NotificationKey key : keys) {
      if (values.length() > 0) values.append(",");
      values
          .append("(?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::timestamptz,?::timestamptz)");
      bindings.add(UUID.randomUUID());
      bindings.add(tenantId);
      bindings.add(membershipId);
      bindings.add(key.type());
      bindings.add(key.ref());
      bindings.add(now);
      bindings.add(now);
    }
    List<NotificationReadEntry> read = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "insert into notification_read_state"
                + "(id,tenant_id,membership_id,notification_type,notification_ref,read_at,created_at)"
                + " values "
                + values
                + " on conflict (tenant_id,membership_id,notification_type,notification_ref)"
                + " do update set read_at = excluded.read_at"
                + " returning notification_type, notification_ref, read_at",
            bindings.toArray())) {
      read.add(toEntry(row));
    }
    return new NotificationReadResult(read, read.size());
  }

  /**
   * 取消已读（**批量**：只对**点名**的这些键生效）。
   *
   * <p><b>幂等</b>：本来就没读过时也算成功 —— 「标成未读」不是一个需要区分
   * 「本来就没有」的动作，界面上点一下就该变成未读，不该因为重复点击报错。
   */
  public NotificationReadResult markUnread(
      UUID tenantId, UUID membershipId, List<NotificationKey> keys) {
    if (keys.isEmpty()) return new NotificationReadResult(List.of(), 0);
    List<Object> bindings = new ArrayList<>();
    bindings.add(tenantId);
    bindings.add(membershipId);
    String tuples = tuplePlaceholders(keys.size());
    for (NotificationKey key : keys) {
      bindings.add(key.type());
      bindings.add(key.ref());
    }
    int affected =
        sql.execute(
            "delete from notification_read_state"
                + " where tenant_id = ?::uuid and membership_id = ?::uuid"
                + " and (notification_type, notification_ref) in ("
                + tuples
                + ")",
            bindings.toArray());
    return new NotificationReadResult(List.of(), affected);
  }

  // ================================================================ 按类型订阅

  /**
   * 我的订阅类型集合（**生效值**）。
   *
   * <p><b>没自定义过时是全部类型</b>：订阅表里没有我的行 = 我没做过选择 = 全部都要。
   * 这样「从没订阅过」与「明确订阅了全部」在行为上完全一致，不需要额外的标记位。
   */
  public NotificationSubscriptions subscriptions(UUID tenantId, UUID membershipId) {
    List<String> stored = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select notification_type from notification_type_subscription"
                + " where tenant_id = ?::uuid and membership_id = ?::uuid"
                + " order by notification_type",
            tenantId,
            membershipId)) {
      stored.add(row.get("notification_type", String.class));
    }
    if (stored.isEmpty()) return new NotificationSubscriptions(NotificationInboxRules.typeCodes());
    // 按词表顺序返回（存储里是按字母序排的，界面上的顺序应当跟词表一致）。
    List<String> ordered = new ArrayList<>(stored.size());
    for (String code : NotificationInboxRules.typeCodes()) {
      if (stored.contains(code)) ordered.add(code);
    }
    return new NotificationSubscriptions(ordered);
  }

  /**
   * 设置我的订阅类型（**整体替换**，不是增量）。
   *
   * <p>替换而不是增量：订阅是一个「集合」语义 —— 界面上的复选框提交的就是
   * 当前勾选的全集。增量会让「取消勾选」永远删不掉某一项。
   */
  public NotificationSubscriptions setSubscriptions(
      UUID tenantId, UUID membershipId, List<String> types) {
    List<String> wanted = NotificationInboxRules.normalizeTypes(types);
    sql.execute(
        "delete from notification_type_subscription"
            + " where tenant_id = ?::uuid and membership_id = ?::uuid",
        tenantId,
        membershipId);
    if (!wanted.isEmpty()) {
      List<Object> bindings = new ArrayList<>();
      StringBuilder values = new StringBuilder();
      for (String code : wanted) {
        if (values.length() > 0) values.append(",");
        values.append("(?::uuid,?::uuid,?::uuid,?::varchar,?::timestamptz)");
        bindings.add(UUID.randomUUID());
        bindings.add(tenantId);
        bindings.add(membershipId);
        bindings.add(code);
        bindings.add(OffsetDateTime.now(ZoneOffset.UTC));
      }
      sql.execute(
          "insert into notification_type_subscription"
              + "(id,tenant_id,membership_id,notification_type,created_at) values "
              + values,
          bindings.toArray());
    }
    return new NotificationSubscriptions(wanted);
  }

  // ================================================================ 内部

  /** 一行 → 一条已读记录。 */
  private NotificationReadEntry toEntry(Record row) {
    String type = row.get("notification_type", String.class);
    String ref = row.get("notification_ref", String.class);
    return new NotificationReadEntry(
        type + ":" + ref, type, ref, row.get("read_at", OffsetDateTime.class));
  }

  /** 生成 {@code (?,?),(?,?),...} 形状的占位符（**值全部走绑定**，不拼进 SQL 文本）。 */
  private static String tuplePlaceholders(int count) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < count; i++) {
      if (i > 0) out.append(",");
      out.append("(?::varchar,?::varchar)");
    }
    return out.toString();
  }
}
