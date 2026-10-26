package com.starcon.identity;

import static com.starcon.identity.UserPreferenceModels.UserPreferenceInput;
import static com.starcon.identity.UserPreferenceModels.UserPreferenceView;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 1.4.1.2 列表与表格偏好 —— 「个人视图状态」的读写。
 *
 * <p><b>这是纯个人偏好数据</b>：它不是业务事实，而是「这个人上次在这个列表页把视图调成什么样」。
 * 写入所有权在本服务（identity，与 1.4 个人设置同域），不在任何业务服务。
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：两个都不是请求参数，更不是请求头。
 * 伪造 {@code X-Tenant-Id} 头改不动 {@code app.current_tenant}，也拿不到别人的 membership_id。
 *
 * <p><b>「只看自己的」在两层保证</b>：RLS 保证「本租户」，SQL 里的 {@code membership_id = ?}
 * 保证「本租户里的这个人」。少任何一层都会变成「能看同事的列表偏好」。
 *
 * <p><b>为什么用 {@code ?::timestamptz} 而不是 {@code Timestamp.valueOf(...)}</b>：
 * 后者按 JVM 默认时区解释 {@code LocalDateTime}，在东八区会静默偏 −28800s。
 * 这里统一用 {@link OffsetDateTime} 绑定。
 */
@Service
public class UserPreferenceService {

  private final DSLContext sql;

  public UserPreferenceService(DSLContext sql) {
    this.sql = sql;
  }

  /**
   * 读一个页面 key 的偏好。
   *
   * <p><b>不存在时返回 {@code stored=false} 而不是 404</b>：「还没保存过偏好」是列表页的
   * 常态，不是异常。界面拿到 {@code stored=false} 就直接用默认值渲染，不该弹错误。
   */
  public UserPreferenceView read(UUID tenantId, UUID membershipId, String pageKey) {
    Record row =
        sql.fetchOne(
            "select preference::text as pref, created_at, updated_at from user_preference"
                + " where tenant_id = ?::uuid and membership_id = ?::uuid and page_key = ?::varchar",
            tenantId,
            membershipId,
            pageKey);
    if (row == null) {
      return new UserPreferenceView(pageKey, false, Map.of(), null, null);
    }
    return new UserPreferenceView(
        pageKey,
        true,
        UserPreferencePayloadRules.read(row.get("pref", String.class)),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 保存一个页面 key 的偏好（**upsert**：同一 page_key 覆盖，不新增行）。
   *
   * <p>写之前先过 {@link UserPreferencePayloadRules#validate}：形状、键名、深度、体积与
   * 敏感字段都在这里被拒掉，**存进表里的东西一定是校验过的**。
   */
  public UserPreferenceView save(
      UUID tenantId, UUID membershipId, String pageKey, UserPreferenceInput input) {
    Map<String, Object> payload =
        UserPreferencePayloadRules.validate(input == null ? null : input.preference());
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Record row =
        sql.fetchOne(
            "insert into user_preference"
                + "(id,tenant_id,membership_id,page_key,preference,created_at,updated_at)"
                + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::jsonb,?::timestamptz,?::timestamptz)"
                + " on conflict (tenant_id,membership_id,page_key) do update"
                + " set preference = excluded.preference, updated_at = excluded.updated_at"
                + " returning created_at, updated_at",
            UUID.randomUUID(),
            tenantId,
            membershipId,
            pageKey,
            UserPreferencePayloadRules.write(payload),
            now,
            now);
    return new UserPreferenceView(
        pageKey,
        true,
        payload,
        row == null ? null : row.get("created_at", OffsetDateTime.class),
        row == null ? null : row.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 重置一个页面 key 的偏好（删掉那一行 = 回到默认值）。
   *
   * <p><b>幂等</b>：本来就没存过时删 0 行也算成功 —— 「重置」不是一个需要区分「本来就没有」
   * 的动作，界面上「重置」按钮按下去就该回到默认，不该因为本来是默认就报错。
   *
   * @return 真正删掉的行数（0 或 1）
   */
  public int reset(UUID tenantId, UUID membershipId, String pageKey) {
    return sql.execute(
        "delete from user_preference"
            + " where tenant_id = ?::uuid and membership_id = ?::uuid and page_key = ?::varchar",
        tenantId,
        membershipId,
        pageKey);
  }

  /** 重置我全部页面的偏好（设置页的「全部恢复默认」）。 */
  public int resetAll(UUID tenantId, UUID membershipId) {
    return sql.execute(
        "delete from user_preference where tenant_id = ?::uuid and membership_id = ?::uuid",
        tenantId,
        membershipId);
  }

  /** 列出我全部页面的偏好（按 page_key 排序）。 */
  public List<UserPreferenceView> list(UUID tenantId, UUID membershipId) {
    List<UserPreferenceView> entries = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select page_key, preference::text as pref, created_at, updated_at from user_preference"
                + " where tenant_id = ?::uuid and membership_id = ?::uuid"
                + " order by page_key",
            tenantId,
            membershipId)) {
      entries.add(
          new UserPreferenceView(
              row.get("page_key", String.class),
              true,
              UserPreferencePayloadRules.read(row.get("pref", String.class)),
              row.get("created_at", OffsetDateTime.class),
              row.get("updated_at", OffsetDateTime.class)));
    }
    return entries;
  }
}
