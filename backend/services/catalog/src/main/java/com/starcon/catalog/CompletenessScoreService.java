package com.starcon.catalog;

import static com.starcon.catalog.CompletenessScoreModels.*;
import static com.starcon.catalog.CompletenessScoreRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.9.2.1 完整度评分：给每个 SPU/SKU 打主数据完整度分（必填属性、类目必填、 图片数量与尺寸、报关属性、包装尺寸重量、认证），按分数分档， 输出蓝图点名的三件东西：评分卡 +
 * 缺失项清单 + 批量补全入口。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>评分一律现算（规则 R1）</b>。评分卡永远从主数据实时算，不读快照 —— 完整度的输入每时每刻都在变，缓存的分数一旦过期就是错的分数，
 *       而错分数比没有分数危险得多（它看起来是有依据的）。 {@code completeness_evaluation} 只回答「过去某一刻」，不参与任何读出口。
 *   <li><b>SPU 与 SKU 用同一套「检查项」列形状</b>（{@code c_<dim>_ok} / {@code c_<dim>_total}）， 于是两条 {@code
 *       UNION ALL} 之后是一张同构表，列表 / 概览 / 评分卡 / 预检 四条读出口都只需要一个查询，且加一个新维度时不必改 UNION 两侧的形状。 每个维度的 {@code
 *       total >= 1} 由规则 R4 保证（无变体的 SPU 记一条缺失项， 而不是把权重摊给别人），所以比率那边<b>永远不除零</b>。
 *   <li><b>口径不散成几份</b>（V37 第七节）：权重 / 分档阈值 / 两个就绪门槛的唯一实现 是 {@link CompletenessScoreRules}；本类用 {@link
 *       #weightedScoreExpression(String)} 把它们<b>拼</b>进 SQL（用于列表按分档筛选与概览分组），SQL 里没有第二份常量。 认证四态的唯一实现是
 *       {@code sku_cert} 聚合，四条读出口全部读它。
 *   <li><b>两个就绪结论分开判（规则 R3）</b>：刊登链与清关链各判各的。
 *   <li><b>缺失项必须可解释</b>：每条缺失带缺失码、主体名、下一步动作， 而不是只给一个计数 —— 「缺 3 条必填属性」没法让人去补。
 *   <li><b>分页是真分页</b>：筛选、排序与 {@code LIMIT/OFFSET} 全部落在 SQL 上， 评分在 SQL 里现算，不在内存里分页。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}， 与同章的 3.6.2.1 供应商报价完全一致 ——
 * 本模块是商品主数据的数据质量面。 <b>刻意不混用两套权限</b>：报关与认证虽属合规域，但混用会出现 「能看评分却改不了报关属性」「能改认证却看不到评分卡」这种半截权限。
 *
 * <p>租户一律来自已认证成员关系经 RLS 生效的连接上下文，<b>不读 {@code X-Tenant-Id} 头</b>。
 */
@Service
public class CompletenessScoreService {
  // ================================================================ SQL 片段

  /**
   * {@code sku_cert}：某个 SKU 的认证四态（规则 R5 的<b>唯一</b>实现）。
   *
   * <p>只统计 {@code scored=true}：运营显式标了「这张证书不影响评分」的记录 不该让维度变红（它仍留在档案里可查）。
   *
   * <p>顺序刻意是 NOT_REQUIRED → 有效 HELD → EXPIRED → PENDING → NONE。 到期日当天仍然有效（闭区间），避免「到期当天变红」这种必然踩坑。
   */
  /**
   * 当前租户的 SQL 表达式：<b>从连接上下文读</b>，不从参数传。
   *
   * <p>{@code AccessService.run} 已经把租户用 {@code set_config('app.current_tenant', ...)} 绑在这条连接上（RLS
   * 策略读的也正是它）。评分 SQL 刻意<b>不</b>再把租户作为 {@code ?} 绑一遍，理由有两条，都不是风格问题：
   *
   * <ul>
   *   <li><b>少一处能错的地方</b>：十个 CTE 各带一个 {@code tenant_id = ?}， 少绑一个就在运行时报「未设定参数值 N」，而 N 指向的参数恰好是另一个合法
   *       租户 id 时，报错完全指不到真正的原因（症状是某条筛选神秘失效或分数错乱）。
   *   <li><b>更贴 AGENTS.md</b>：租户来自已认证成员关系，绑定在连接上就是它的落点； 再从入参传一遍，等于给了一条"理论上能传错租户"的旁路。
   * </ul>
   */
  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  private static final String CTE_SKU_CERT =
      """
      sku_cert as (
        select c.tenant_id, c.sku_id,
          coalesce(bool_or(c.status='NOT_REQUIRED'), false) as has_not_required,
          coalesce(bool_or(c.status='HELD'
            and (c.expires_on is null or c.expires_on >= current_date)), false) as has_valid_held,
          coalesce(bool_or(c.status='EXPIRED'
            or (c.status='HELD' and c.expires_on is not null and c.expires_on < current_date)), false) as has_expired,
          coalesce(bool_or(c.status='PENDING'), false) as has_pending
        from sku_certification c
        where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and c.scored
        group by c.tenant_id, c.sku_id
      )""";

  /** 变体级必填属性：SKU 的必填属性集来自其 SPU 的属性定义。 */
  private static final String CTE_SKU_ATTR =
      """
      sku_attr as (
        select k.tenant_id, k.id as sku_id,
          count(*) as attr_total,
          count(*) filter (where coalesce(vs.id, vp.id) is not null) as attr_ok
        from sku k
        join attribute_definition d
          on d.tenant_id = k.tenant_id and d.spu_id = k.spu_id and d.required
        left join attribute_value vs
          on vs.tenant_id = d.tenant_id and vs.attribute_id = d.id and vs.sku_id = k.id and vs.state = 'SET'
         and exists (select 1 from attribute_value_item i where i.tenant_id = vs.tenant_id and i.value_id = vs.id)
        left join attribute_value vp
          on vp.tenant_id = d.tenant_id and vp.attribute_id = d.id and vp.sku_id is null and vp.state = 'SET'
         and exists (select 1 from attribute_value_item i where i.tenant_id = vp.tenant_id and i.value_id = vp.id)
        where k.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
        group by k.tenant_id, k.id
      )""";

  /**
   * 变体级类目规则：取 SKU 所属 SPU 的主分类下 {@code requirement='REQUIRED'} 的规则， 用 SKU 的<b>有效值</b>（变体值或公共值）判是否已填。
   *
   * <p>刻意只取无条件规则（规则 R9）：条件必填是录入期的表单规则， 求值必须复用 3.3.1.2 的 {@code conditionHolds} 唯一实现，抄进评分 SQL
   * 就是第二份算子语义。
   *
   * <p>别名用 {@code pcat} 而不是 {@code sc}：PG 会把只被引用一次的 CTE <b>内联</b>展开到外层， 而外层已经用 {@code sc} 指 {@code
   * sku_cat} —— 内层的 {@code sc} 与外层的 {@code sc} 同名时 PG 会把引用解析到内层去，于是报 {@code missing FROM-clause
   * entry for table "sc"}。 这个别名冲突只在<b>运行期</b>出现（编译与单测都看不出来），所以别名必须全局唯一。
   */
  private static final String CTE_SKU_CAT =
      """
      sku_cat as (
        select k.tenant_id, k.id as sku_id,
          (pcat.spu_id is not null) as has_category,
          count(r.attribute_id) as rule_total,
          count(r.attribute_id) filter (where coalesce(vs.id, vp.id) is not null) as rule_ok
        from sku k
        left join spu_category pcat
          on pcat.tenant_id = k.tenant_id and pcat.spu_id = k.spu_id and pcat.role = 'PRIMARY'
        left join category_attribute_rule r
          on r.tenant_id = pcat.tenant_id and r.category_id = pcat.category_id and r.requirement = 'REQUIRED'
        left join attribute_value vs
          on vs.tenant_id = r.tenant_id and vs.attribute_id = r.attribute_id and vs.sku_id = k.id and vs.state = 'SET'
         and exists (select 1 from attribute_value_item i where i.tenant_id = vs.tenant_id and i.value_id = vs.id)
        left join attribute_value vp
          on vp.tenant_id = r.tenant_id and vp.attribute_id = r.attribute_id and vp.sku_id is null and vp.state = 'SET'
         and exists (select 1 from attribute_value_item i where i.tenant_id = vp.tenant_id and i.value_id = vp.id)
        where k.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
        group by k.tenant_id, k.id, pcat.spu_id
      )""";

  /**
   * 媒体事实：按 {@code media_reference} 的 {@code owner_type} 分两侧聚合。
   *
   * <p><b>SPU 侧刻意把「自己挂的图」与「它每个变体挂的图」并起来</b>： 刊登时取的是变体的图，而一个 SPU 判定为"没图"（图全挂在变体上）会让人以为 真缺图 —— 症状是
   * SPU 评分卡说图片维度缺失、而每个变体的评分卡都说图片齐全， 两边都在讲同一个商品。这类"同一件事在两个层级给出矛盾答案"是最难查的一类错。
   * 变体自己的评分卡仍然只看它自己那几张（变体之间图的数量本就不同）。
   */
  private static final String CTE_SKU_MEDIA =
      """
      sku_media as (
        select r.sku_id,
          count(*) filter (where a.media_type = 'IMAGE') as image_count,
          count(*) filter (where a.media_type = 'IMAGE' and a.purpose = 'PRIMARY') as primary_count,
          coalesce(min(least(coalesce(a.width_px,0), coalesce(a.height_px,0)))
            filter (where a.media_type = 'IMAGE' and a.purpose = 'PRIMARY'), 0) as primary_edge
        from media_reference r
        join media_asset a on a.tenant_id = r.tenant_id and a.id = r.asset_id
        where r.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and r.owner_type = 'SKU' and r.sku_id is not null and a.enabled
        group by r.sku_id
      ),
      spu_media as (
        select u.spu_id,
          count(*) filter (where u.media_type = 'IMAGE') as image_count,
          count(*) filter (where u.media_type = 'IMAGE' and u.purpose = 'PRIMARY') as primary_count,
          coalesce(min(least(coalesce(u.width_px,0), coalesce(u.height_px,0)))
            filter (where u.media_type = 'IMAGE' and u.purpose = 'PRIMARY'), 0) as primary_edge
        from (
          select r.spu_id, a.media_type, a.purpose, a.width_px, a.height_px
          from media_reference r
          join media_asset a on a.tenant_id = r.tenant_id and a.id = r.asset_id
          where r.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and r.owner_type = 'SPU' and r.spu_id is not null and a.enabled
          union all
          select k.spu_id, a.media_type, a.purpose, a.width_px, a.height_px
          from media_reference r
          join sku k on k.tenant_id = r.tenant_id and k.id = r.sku_id
          join media_asset a on a.tenant_id = r.tenant_id and a.id = r.asset_id
          where r.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and r.owner_type = 'SKU' and r.sku_id is not null and a.enabled
        ) u
        group by u.spu_id
      )""";

  /**
   * {@code sku_dim}：变体侧的检查项计数。
   *
   * <p>六个维度全部输出同名的 {@code c_<dim>_ok} / {@code c_<dim>_total}， 且 {@code total >= 1}：
   *
   * <ul>
   *   <li>属性：没有必填属性定义时 {@code total=1, ok=1}（"没定义必填属性"不是缺失， 否则一个还没配必填属性的新 SPU 会永远红着，而运营不知道怎么让它变绿）。
   *   <li>图片：固定三条（有无主图 / 张数 / 主图最短边），每条都能独立报告。
   *   <li>报关：四条（有无报关行 / HS / 申报品名 / 原产国）。
   *   <li>包装：三条（有无包装行 / 三边 / 净毛重）。
   *   <li>认证：一条（四态里只有 COMPLETE 算齐）。
   * </ul>
   */
  private static final String CTE_SKU_DIM =
      """
      sku_dim as (
        select k.tenant_id, k.spu_id, k.id as owner_id, k.code as owner_code, k.name as owner_name,
          k.status as owner_status, k.updated_at,
          greatest(coalesce(sa.attr_total, 0), 1) as c_attribute_total,
          (case when coalesce(sa.attr_total, 0) = 0 then 1 else coalesce(sa.attr_ok, 0) end) as c_attribute_ok,
          3 as c_media_total,
          (case when coalesce(sm.primary_count, 0) >= 1 then 1 else 0 end)
          + (case when coalesce(sm.image_count, 0) >= %d then 1 else 0 end)
          + (case when coalesce(sm.primary_edge, 0) >= %d then 1 else 0 end) as c_media_ok,
          (1 + coalesce(scat.rule_total, 0)) as c_category_total,
          (case when coalesce(scat.has_category, false) then 1 else 0 end) + coalesce(scat.rule_ok, 0) as c_category_ok,
          4 as c_customs_total,
          (case when d.id is not null then 1 else 0 end)
          + (case when coalesce(btrim(d.hs_code), '') <> '' then 1 else 0 end)
          + (case when coalesce(btrim(d.declared_name), '') <> '' then 1 else 0 end)
          + (case when coalesce(btrim(d.origin_country), '') <> '' then 1 else 0 end) as c_customs_ok,
          3 as c_packaging_total,
          (case when p.id is not null then 1 else 0 end)
          + (case when p.id is not null and coalesce(p.length_mm,0) > 0 and coalesce(p.width_mm,0) > 0
                   and coalesce(p.height_mm,0) > 0 then 1 else 0 end)
          + (case when p.id is not null and coalesce(p.net_weight_g,0) > 0
                   and coalesce(p.gross_weight_g,0) > 0 then 1 else 0 end) as c_packaging_ok,
          1 as c_certification_total,
          (case when ct.has_not_required or ct.has_valid_held then 1 else 0 end) as c_certification_ok,
          -- 展示与补全清单要用的明细（比率算不出来时它们才有用）
          coalesce(sm.image_count, 0) as image_count, coalesce(sm.primary_count, 0) as primary_count,
          coalesce(sm.primary_edge, 0) as primary_edge,
          (d.id is not null) as customs_present,
          coalesce(btrim(d.hs_code), '') as hs_code,
          coalesce(btrim(d.declared_name), '') as declared_name,
          coalesce(btrim(d.origin_country), '') as origin_country,
          (p.id is not null) as pkg_present,
          (p.id is not null and coalesce(p.length_mm,0) > 0 and coalesce(p.width_mm,0) > 0
             and coalesce(p.height_mm,0) > 0) as pkg_dims_ok,
          (p.id is not null and coalesce(p.net_weight_g,0) > 0 and coalesce(p.gross_weight_g,0) > 0) as pkg_weights_ok,
          coalesce(scat.has_category, false) as has_category,
          coalesce(scat.rule_total, 0) as rule_total, coalesce(scat.rule_ok, 0) as rule_ok,
          (case when ct.sku_id is null then 'NONE'
                when ct.has_not_required or ct.has_valid_held then 'COMPLETE'
                when ct.has_expired then 'EXPIRED'
                when ct.has_pending then 'PENDING'
                else 'NONE' end) as cert_completeness
        from sku k
        left join sku_attr sa on sa.sku_id = k.id
        left join sku_cat scat on scat.sku_id = k.id
        left join sku_media sm on sm.sku_id = k.id
        left join sku_package p on p.tenant_id = k.tenant_id and p.sku_id = k.id
        left join sku_customs_declaration d on d.tenant_id = k.tenant_id and d.sku_id = k.id and d.enabled
        left join sku_cert ct on ct.sku_id = k.id
        where k.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
      )""";

  /**
   * {@code spu_dim}：SPU 侧的检查项计数。
   *
   * <p>三条 SKU 侧维度（报关 / 包装 / 认证）按「齐不齐的变体数」汇总， 分母是 {@code greatest(sku_count, 1)} —— <b>没有变体时记一条
   * {@code SPU_HAS_NO_SKU} 缺失项</b>而不是把权重摊给别的维度（规则 R4）： 摊权重会让「一个变体都没有的商品」在属性与图片上拿满分， 看起来比它实际的状态好得多。
   */
  private static final String CTE_SPU_DIM =
      """
      spu_attr as (
        select s.tenant_id, s.id as spu_id,
          count(*) as attr_total,
          count(*) filter (where v.id is not null) as attr_ok
        from spu s
        join attribute_definition d
          on d.tenant_id = s.tenant_id and d.spu_id = s.id and d.required and not d.variant_axis
        left join attribute_value v
          on v.tenant_id = d.tenant_id and v.attribute_id = d.id and v.sku_id is null and v.state = 'SET'
         and exists (select 1 from attribute_value_item i where i.tenant_id = v.tenant_id and i.value_id = v.id)
        where s.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
        group by s.tenant_id, s.id
      ),
      spu_cat as (
        select pcat.tenant_id, pcat.spu_id,
          count(r.attribute_id) as rule_total,
          count(r.attribute_id) filter (where v.id is not null) as rule_ok
        from spu_category pcat
        join category_attribute_rule r
          on r.tenant_id = pcat.tenant_id and r.category_id = pcat.category_id and r.requirement = 'REQUIRED'
        left join attribute_value v
          on v.tenant_id = r.tenant_id and v.attribute_id = r.attribute_id and v.sku_id is null and v.state = 'SET'
         and exists (select 1 from attribute_value_item i where i.tenant_id = v.tenant_id and i.value_id = v.id)
        where pcat.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and pcat.role = 'PRIMARY'
        group by pcat.tenant_id, pcat.spu_id
      ),
      spu_dim as (
        select s.tenant_id, s.id as spu_id, s.id as owner_id, s.code as owner_code, s.name as owner_name,
          s.status as owner_status, s.updated_at, cat.name as category_name,
          greatest(coalesce(sa.attr_total, 0), 1) as c_attribute_total,
          (case when coalesce(sa.attr_total, 0) = 0 then 1 else coalesce(sa.attr_ok, 0) end) as c_attribute_ok,
          3 as c_media_total,
          (case when coalesce(sm.primary_count, 0) >= 1 then 1 else 0 end)
          + (case when coalesce(sm.image_count, 0) >= %d then 1 else 0 end)
          + (case when coalesce(sm.primary_edge, 0) >= %d then 1 else 0 end) as c_media_ok,
          (1 + coalesce(scat.rule_total, 0)) as c_category_total,
          (case when pcat.spu_id is not null then 1 else 0 end) + coalesce(scat.rule_ok, 0) as c_category_ok,
          greatest(coalesce(k.skus, 0), 1) as c_customs_total,
          (case when coalesce(k.skus, 0) = 0 then 0 else coalesce(k.customs_ok, 0) end) as c_customs_ok,
          greatest(coalesce(k.skus, 0), 1) as c_packaging_total,
          (case when coalesce(k.skus, 0) = 0 then 0 else coalesce(k.packaging_ok, 0) end) as c_packaging_ok,
          greatest(coalesce(k.skus, 0), 1) as c_certification_total,
          (case when coalesce(k.skus, 0) = 0 then 0 else coalesce(k.cert_ok, 0) end) as c_certification_ok,
          coalesce(k.skus, 0) as sku_count,
          coalesce(sm.image_count, 0) as image_count, coalesce(sm.primary_count, 0) as primary_count,
          coalesce(sm.primary_edge, 0) as primary_edge,
          (pcat.spu_id is not null) as has_category,
          coalesce(scat.rule_total, 0) as rule_total, coalesce(scat.rule_ok, 0) as rule_ok
        from spu s
        left join spu_attr sa on sa.spu_id = s.id
        left join spu_category pcat
          on pcat.tenant_id = s.tenant_id and pcat.spu_id = s.id and pcat.role = 'PRIMARY'
        left join spu_cat scat on scat.spu_id = s.id
        left join product_category cat on cat.tenant_id = pcat.tenant_id and cat.id = pcat.category_id
        left join spu_media sm on sm.spu_id = s.id
        left join lateral (
          select count(*) as skus,
            count(*) filter (where d.c_customs_ok = d.c_customs_total) as customs_ok,
            count(*) filter (where d.c_packaging_ok = d.c_packaging_total) as packaging_ok,
            count(*) filter (where d.c_certification_ok = d.c_certification_total) as cert_ok
          from sku_dim d where d.tenant_id = s.tenant_id and d.spu_id = s.id
        ) k on true
        where s.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
      )""";

  private static final String CTE_OWNER_DIM =
      """
      owner_dim as (
        select 'SKU'::text as owner_type, d.tenant_id, d.spu_id, d.owner_id, d.owner_code,
          d.owner_name, d.owner_status, null::text as category_name, d.updated_at,
          d.c_attribute_ok, d.c_attribute_total, d.c_media_ok, d.c_media_total,
          d.c_category_ok, d.c_category_total, d.c_customs_ok, d.c_customs_total,
          d.c_packaging_ok, d.c_packaging_total, d.c_certification_ok, d.c_certification_total,
          1::bigint as sku_count,
          d.image_count, d.primary_count, d.primary_edge,
          d.customs_present, d.hs_code, d.declared_name, d.origin_country,
          d.pkg_present, d.pkg_dims_ok, d.pkg_weights_ok, d.cert_completeness,
          d.has_category, d.rule_total, d.rule_ok
        from sku_dim d
        union all
        select 'SPU'::text as owner_type, d.tenant_id, d.spu_id, d.owner_id, d.owner_code,
          d.owner_name, d.owner_status, d.category_name, d.updated_at,
          d.c_attribute_ok, d.c_attribute_total, d.c_media_ok, d.c_media_total,
          d.c_category_ok, d.c_category_total, d.c_customs_ok, d.c_customs_total,
          d.c_packaging_ok, d.c_packaging_total, d.c_certification_ok, d.c_certification_total,
          d.sku_count,
          d.image_count, d.primary_count, d.primary_edge,
          false as customs_present, ''::text as hs_code, ''::text as declared_name, ''::text as origin_country,
          false as pkg_present, false as pkg_dims_ok, false as pkg_weights_ok, null as cert_completeness,
          d.has_category, d.rule_total, d.rule_ok
        from spu_dim d
      )""";

  /**
   * {@code owner_scored} 里租户占位符的个数。
   *
   * <p>逐个数过：sku_cert(1) + sku_attr(1) + sku_cat(1) + sku_media(2) + sku_dim(1) + spu_attr(1) +
   * spu_cat(1) + spu_dim(1) = 9。 刻意用常量而不是"数一遍问号"：jOOQ 按<b>文本出现顺序</b>绑定， 少绑一个会在运行时报「未设定参数值 N 的内容」，而
   * N 指向的参数恰好是另一个合法 租户 id 时，报错完全指不到真正的原因。
   */
  private static final int TENANT_BIND_COUNT = 9; // 仅供文档；实际个数由 tenantBinds 从 SQL 文本数出

  /**
   * 快照两列 jsonb 的序列化。
   *
   * <p>刻意<b>不</b>复用 {@code ImportTemplateService.json}：那是 3.8.1.1 的私有工具， 签名只吃 {@code
   * Map<String,String>}，而这里要存的是两个结构不同的数组 （维度缺口与缺失项）。跨模块借一个签名不匹配的私有方法，等于把两个模块焊在一起。
   */
  private static final ObjectMapper JSON = new ObjectMapper();

  private final DSLContext sql;

  public CompletenessScoreService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 表达式拼装（唯一实现的注入点）

  /**
   * 用 {@link CompletenessScoreRules} 的权重<b>拼</b>出 SQL 里的加权求和表达式。
   *
   * <p>这是"口径不散成几份"的关键：SQL 里<b>没有</b>第二份 {@code 0.25} 常量。 改一个权重，表达式自动跟着变，不存在"改了 Java 忘了改
   * SQL"（那种分叉的症状是 分数整体偏移且没有任何报错 —— 页面上看不出任何异常，只是全店都低了 2 分）。
   *
   * @param columnPrefix 比率列名前缀，例如 {@code ""} 或 {@code "s."}
   */
  static String weightedScoreExpression(String columnPrefix) {
    var terms = new ArrayList<String>();
    var weights = new ArrayList<String>();
    for (CompletenessScoreRules.Dimension dimension : dimensions()) {
      String ratio = columnPrefix + ratioColumn(dimension.code());
      terms.add(dimension.weight().toPlainString() + " * coalesce(" + ratio + ", 1)");
      weights.add(dimension.weight().toPlainString());
    }
    String numerator = String.join(" + ", terms);
    String denominator = String.join(" + ", weights);
    return "round((" + numerator + ") / nullif(" + denominator + ", 0), " + SCORE_SCALE + ")";
  }

  /** 维度码 → 比率列名。六个维度共用一套命名，加维度时只改这里。 */
  static String ratioColumn(String dimensionCode) {
    return dimensionCode.toLowerCase(java.util.Locale.ROOT) + "_ratio";
  }

  /** 分档 CASE 表达式（阈值同样来自 {@link CompletenessScoreRules}）。 */
  static String gradeCase(String scoreExpression) {
    return "(case when "
        + scoreExpression
        + " >= "
        + excellentThreshold().toPlainString()
        + " then 'EXCELLENT' when "
        + scoreExpression
        + " >= "
        + goodThreshold().toPlainString()
        + " then 'GOOD' when "
        + scoreExpression
        + " >= "
        + fairThreshold().toPlainString()
        + " then 'FAIR' else 'POOR' end)";
  }

  /** 六个维度的比率表达式（每个都是 {@code ok::numeric / total::numeric}，分母恒 >= 1）。 */
  private static String ratioColumnSql(String prefix) {
    StringBuilder sql = new StringBuilder();
    for (CompletenessScoreRules.Dimension dimension : dimensions()) {
      String key = dimension.code().toLowerCase(java.util.Locale.ROOT);
      if (sql.length() > 0) sql.append(", ");
      sql.append("round(")
          .append(prefix)
          .append("c_")
          .append(key)
          .append("_ok::numeric / ")
          .append(prefix)
          .append("c_")
          .append(key)
          .append("_total::numeric, ")
          .append(SCORE_SCALE)
          .append(") as ")
          .append(ratioColumn(dimension.code()));
    }
    return sql.toString();
  }

  /** 缺失项数表达式：{@code Σ(total - ok)}（六项之和，一个常数）。 */
  private static String missingCountSql() {
    return missingCountSql("");
  }

  private static String missingCountSql(String prefix) {
    var terms = new ArrayList<String>();
    for (CompletenessScoreRules.Dimension dimension : dimensions()) {
      String key = dimension.code().toLowerCase(java.util.Locale.ROOT);
      terms.add("(" + prefix + "c_" + key + "_total - " + prefix + "c_" + key + "_ok)");
    }
    return "(" + String.join(" + ", terms) + ")";
  }

  /**
   * {@code owner_scored}：给 {@code owner_dim} 补上六个比率、缺失数、分数与分档。
   *
   * <p>这是列表 / 概览 / 评分卡 / 预检四条读出口共用的最后一层， 所以「按分档筛选」与「按就绪状态筛选」才能真的下推到 SQL（真分页的前提）。
   */
  private String ownerScoredSql() {
    String ratios = ratioColumnSql("");
    String score = weightedScoreExpression("r.");
    String grade = gradeCase(score);
    // 两层而不是一层：PostgreSQL **不允许**在同一条 SELECT 的输出列表里引用同级别名
    // （`select a/b as r, 0.25*r ...` 会报 column "r" does not exist）。
    // 所以比率先在 owner_ratios 里算出来，分数/分档/就绪再在 owner_scored 里引用它们。
    //
    // 用 StringBuilder 拼而不是"文本块 + 拼接"：Java 的文本块分隔符要求起始符后必须
    // 紧跟行终止符，`return """\n  with """ + CTE + """,` 这种把下一个文本块的起始符
    // 放在表达式中间的写法编译不过。CTE 常量各自是完整的文本块，这里按它们**在文本里
    // 出现的顺序**拼 —— 顺序错了绑定就会错位（且不会编译报错）。
    return new StringBuilder()
        .append("with ")
        .append(CTE_SKU_CERT)
        .append(",\n")
        .append(CTE_SKU_ATTR)
        .append(",\n")
        .append(CTE_SKU_CAT)
        .append(",\n")
        .append(CTE_SKU_MEDIA)
        .append(",\n")
        .append(String.format(CTE_SKU_DIM, MIN_IMAGE_COUNT, MIN_PRIMARY_IMAGE_EDGE_PX))
        .append(",\n")
        .append(String.format(CTE_SPU_DIM, MIN_IMAGE_COUNT, MIN_PRIMARY_IMAGE_EDGE_PX))
        .append(",\n")
        .append(CTE_OWNER_DIM)
        .append(",\n")
        .append("owner_ratios as (\n")
        .append("  select d.*, ")
        .append(ratios)
        .append("\n")
        .append("  from owner_dim d\n")
        .append("),\n")
        .append("owner_scored as (\n")
        .append("  select r.*, ")
        .append(missingCountSql("r."))
        .append(" as missing_count,\n")
        .append("    ")
        .append(score)
        .append(" as score,\n")
        .append("    ")
        .append(grade)
        .append(" as grade,\n")
        .append("    (")
        .append(listingReadySql(score))
        .append(") as listing_ready,\n")
        .append("    (")
        .append(customsReadySql(score))
        .append(") as customs_ready\n")
        .append("  from owner_ratios r\n")
        .append(")\n")
        .toString();
  }

  /** 刊登就绪：总分过门槛 **且** 刊登链四个维度都无缺失（规则 R3）。 */
  private static String listingReadySql(String scoreExpression) {
    return chainReadySql(scoreExpression, LISTING_CHAIN, LISTING_GATE);
  }

  /** 清关就绪：总分过门槛 **且** 清关链两个维度都无缺失（规则 R3）。 */
  private static String customsReadySql(String scoreExpression) {
    return chainReadySql(scoreExpression, CUSTOMS_CHAIN, CUSTOMS_GATE);
  }

  private static String chainReadySql(String scoreExpression, List<String> chain, BigDecimal gate) {
    var terms = new ArrayList<String>();
    terms.add("(" + scoreExpression + " >= " + gate.toPlainString() + ")");
    for (String dimension : chain) {
      String key = dimension.toLowerCase(java.util.Locale.ROOT);
      // 列名前缀跟着 scoreExpression 走：就绪判定与分数在同一层 CTE，别名前缀必须一致，
      // 写死 "" 的话这一层会找不到 c_* 列，而报错指向的是"列不存在"而不是"前缀漏了"。
      String prefix = scoreExpression.startsWith("r.") ? "r." : "";
      terms.add("(" + prefix + "c_" + key + "_ok = " + prefix + "c_" + key + "_total)");
    }
    return String.join(" and ", terms);
  }

  /**
   * 构造 {@code owner_scored} 的租户绑定参数。
   *
   * <p><b>个数从 SQL 文本里数出来</b>而不是写死常量：每个 CTE 各带一个 {@code tenant_id = ?}，而 CTE 增删是常事。写死个数的那天起，
   * 少绑一个会在运行时报「未设定参数值 N 的内容」—— 而 N 指向的那个参数 恰好是另一个合法租户 id 时，报错完全指不到真正的原因（症状是某条筛选
   * 神秘失效或分数错乱，而不是"少了一个绑定"）。
   */
  private List<Object> tenantBinds(UUID tenantId) {
    // 恒为空：租户从连接上下文读（见 TENANT），评分 SQL 里没有租户占位符。
    // 方法保留是为了让调用点读起来仍是"先铺租户、再铺筛选参数"的顺序，
    // 将来若真需要再加一个 CTE 参数，只需在这里补。
    return new ArrayList<>();
  }

  private static int countChar(String value, char target) {
    int count = 0;
    for (int i = 0; i < value.length(); i++) if (value.charAt(i) == target) count++;
    return count;
  }

  // ================================================================ 词表与概览

  /** 词表选项：前端下拉与阈值展示的数据源（与 {@link CompletenessScoreRules} 同源）。 */
  public CompletenessOptions options() {
    return new CompletenessOptions(
        dimensionOrder(),
        dimensionOptions(),
        List.of("POOR", "FAIR", "GOOD", "EXCELLENT"),
        gradeOptions(),
        List.of(OWNER_SPU, OWNER_SKU),
        List.of("NOT_REQUIRED", "HELD", "PENDING", "EXPIRED"),
        certificationStatusOptions(),
        MIN_IMAGE_COUNT,
        MIN_PRIMARY_IMAGE_EDGE_PX,
        LISTING_GATE.toPlainString(),
        CUSTOMS_GATE.toPlainString(),
        "完整度分 6 个维度加权求和，权重合计 1.0000（必填属性 0.25 / 图片 0.20 / 类目必填 0.15 /"
            + " 报关属性 0.15 / 包装尺寸重量 0.15 / 认证 0.10）。分档阈值作用在 0~1 的比值上："
            + "优秀 ≥ 0.90、良好 ≥ 0.75、一般 ≥ 0.60、较差 < 0.60。"
            + "图片阈值：主图至少 1 张、图片总数至少 "
            + MIN_IMAGE_COUNT
            + " 张、主图最短边至少 "
            + MIN_PRIMARY_IMAGE_EDGE_PX
            + "px。"
            + "刊登就绪要求总分 ≥ "
            + LISTING_GATE.toPlainString()
            + " 且必填属性/图片/类目必填/认证四个维度无缺失；"
            + "清关就绪要求总分 ≥ "
            + CUSTOMS_GATE.toPlainString()
            + " 且报关属性与包装尺寸重量两个维度无缺失。"
            + "认证的「无需认证」与「已取得」都算齐全 —— 否则不需要认证的商品会永远缺一条。");
  }

  /** 概览：台账顶部的治理数字。全部在 SQL 里聚合，不在内存里遍历。 */
  public CompletenessOverview overview() {
    UUID tenantId = currentTenantId();
    String sqlText =
        ownerScoredSql()
            + """
        select
          count(*) filter (where owner_type = 'SPU') as spu_count,
          count(*) filter (where owner_type = 'SKU') as sku_count,
          count(*) filter (where listing_ready) as listing_ready_count,
          count(*) filter (where customs_ready) as customs_ready_count,
          count(*) filter (where owner_type = 'SKU' and not listing_ready) as listing_blocked_count,
          count(*) filter (where owner_type = 'SKU' and not customs_ready) as customs_blocked_count,
          count(*) filter (where owner_type = 'SKU' and not listing_ready and not customs_ready) as both_blocked_count,
          coalesce(avg(score), 0) as avg_score
        from owner_scored""";
    Record r = sql.fetchOne(sqlText, tenantBinds(tenantId).toArray());
    List<GradeCount> gradeCounts = gradeCounts(tenantId, null);
    List<DimensionGap> dimensionGaps = dimensionGaps(tenantId, null);
    long bothBlocked = longOf(r.get("both_blocked_count", Long.class));
    return new CompletenessOverview(
        longOf(r.get("spu_count", Long.class)),
        longOf(r.get("sku_count", Long.class)),
        longOf(r.get("listing_ready_count", Long.class)),
        longOf(r.get("customs_ready_count", Long.class)),
        longOf(r.get("listing_blocked_count", Long.class)),
        longOf(r.get("customs_blocked_count", Long.class)),
        bothBlocked,
        gradeCounts,
        dimensionGaps,
        "完整度分 6 个维度加权求和，权重合计 1.0000。评分一律现算：改动主数据后数字立刻变，"
            + "读出口不读历史快照（快照只回答「当时为什么被拦住」）。"
            + "listingBlocked / customsBlocked 只计 SKU：SPU 的三条 SKU 侧维度是变体汇总，"
            + "把它与变体一起计数会让同一个缺口在两个层级各数一次。");
  }

  /** 分档计数（全量，不受列表筛选影响 —— 它回答的是「全店各档有多少」）。 */
  private List<GradeCount> gradeCounts(UUID tenantId, String ownerType) {
    String where = ownerType == null ? "" : " where owner_type = '" + ownerType + "'";
    String sqlText =
        ownerScoredSql()
            + " select grade, count(*) as c from owner_scored"
            + where
            + " group by grade";
    var counts = new LinkedHashMap<String, Long>();
    for (String grade : List.of("POOR", "FAIR", "GOOD", "EXCELLENT")) counts.put(grade, 0L);
    for (Record r : sql.fetch(sqlText, tenantBinds(tenantId).toArray()))
      counts.put(r.get("grade", String.class), longOf(r.get("c", Long.class)));
    var result = new ArrayList<GradeCount>();
    for (var entry : counts.entrySet())
      result.add(new GradeCount(entry.getKey(), gradeLabel(entry.getKey()), entry.getValue()));
    return result;
  }

  /**
   * 各维度的缺失项数（补全清单顶部的"按维度归集"视图）。
   *
   * <p>在 SQL 里对每个维度各求一次 {@code sum(total - ok)}： 比在 Java 里遍历所有主体再统计省一次全量往返，而结果完全一致 （同一份 {@code
   * owner_scored}，同一个定义）。
   */
  private List<DimensionGap> dimensionGaps(UUID tenantId, String ownerType) {
    var selects = new ArrayList<String>();
    for (CompletenessScoreRules.Dimension dimension : dimensions()) {
      String key = dimension.code().toLowerCase(java.util.Locale.ROOT);
      selects.add(
          "coalesce(sum(c_"
              + key
              + "_total - c_"
              + key
              + "_ok), 0) as \""
              + dimension.code()
              + "\"");
    }
    String where = ownerType == null ? "" : " where owner_type = '" + ownerType + "'";
    String sqlText =
        ownerScoredSql() + " select " + String.join(", ", selects) + " from owner_scored" + where;
    Record r = sql.fetchOne(sqlText, tenantBinds(tenantId).toArray());
    var result = new ArrayList<DimensionGap>();
    for (CompletenessScoreRules.Dimension dimension : dimensions()) {
      long count = longOf(r.get(dimension.code(), Long.class));
      result.add(new DimensionGap(dimension.code(), dimension.label(), count));
    }
    return result;
  }

  // ================================================================ 列表与评分卡

  /**
   * 评分列表：分页 + 关键词 / 主体类型 / 分档 / 变体数 / 状态 / 就绪状态筛选。
   *
   * <p>排序字段走白名单（{@link #normalizeOrderBy}）：排序字段要进 {@code order by}， 而列名不能参数化，不做白名单就是一个注入口。
   */
  public CompletenessPage list(CompletenessQuery query) {
    UUID tenantId = currentTenantId();
    int page = normalizePage(query.page());
    int size = normalizeSize(query.size());
    var filter = new ArrayList<String>();
    // filterBinds 与 filter 一一对应（租户占位符在前，筛选占位符在后 —— 顺序必须与
    // SQL 文本里它们出现的顺序一致）。刻意把它与 itemsBinds 分开：
    // count/ready 两条查询都带 where，占的是同一批筛选参数，但**不带** limit/offset，
    // 混用一次就会在运行时报「未设定参数值 N」—— 而 N 指向的那个参数恰好是另一个合法值时，
    // 报错完全指不到真正的原因。
    var filterBinds = tenantBinds(tenantId);
    applyIdentityFilters(query, filter, filterBinds);

    String where = String.join(" and ", filter);
    String order = orderClause(query);
    // 分页在 SQL 上做：评分是现算的，不能在内存里先全算一遍再切页（那是假分页）。
    String listSql =
        ownerScoredSql()
            + """
        select owner_type, tenant_id, spu_id, owner_id, owner_code, owner_name, owner_status,
          category_name, score, grade, missing_count, sku_count, listing_ready, customs_ready,
          attribute_ratio, media_ratio, category_ratio, customs_ratio, packaging_ratio, certification_ratio
        from owner_scored
        """
            + (where.isEmpty() ? "" : " where " + where)
            + " order by "
            + order
            + " limit ? offset ?";
    var itemsBinds = new ArrayList<Object>(filterBinds);
    itemsBinds.add(size);
    itemsBinds.add(page * size);

    String countSql =
        ownerScoredSql()
            + " select count(*) from owner_scored"
            + (where.isEmpty() ? "" : " where " + where);
    long total = sql.fetchOne(countSql, filterBinds.toArray()).get(0, Long.class);

    var items = new ArrayList<CompletenessRow>();
    for (Record r : sql.fetch(listSql, itemsBinds.toArray())) items.add(toRow(r));

    String readinessWhere = where.isEmpty() ? "" : " where " + where;
    String readySql =
        ownerScoredSql()
            + " select"
            + " count(*) filter (where listing_ready) as lr,"
            + " count(*) filter (where customs_ready) as cr,"
            + " count(*) filter (where not listing_ready and not customs_ready) as blocked"
            + " from owner_scored"
            + readinessWhere;
    Record ready = sql.fetchOne(readySql, filterBinds.toArray());

    return new CompletenessPage(
        items,
        total,
        page,
        size,
        longOf(ready.get("lr", Long.class)),
        longOf(ready.get("cr", Long.class)),
        longOf(ready.get("blocked", Long.class)),
        gradeCounts(tenantId, query.ownerType()),
        "评分一律现算：改动属性、图片、报关或认证之后立刻生效，"
            + "页面上的数字永远是当前主数据的结论（历史分数在评估快照里）。"
            + "listingReady 与 customsReady 是两个独立结论：一个带图有属性但没有 HS 编码的商品"
            + "完全可能刊登就绪、清关未就绪，合并成一个百分比就看不出自己被哪条链拦住。");
  }

  /** 身份类筛选（关键词 / 主体类型 / SPU / 类目 / 状态 / 分档 / 就绪）。 */
  private void applyIdentityFilters(
      CompletenessQuery query, List<String> filter, List<Object> binds) {
    String ownerType = normalizeEnum(query.ownerType());
    if (ownerType != null) {
      if (!OWNER_TYPES.contains(ownerType)) throw badRequest(ownerTypeProblem(query.ownerType()));
      filter.add("owner_type = ?");
      binds.add(ownerType);
    }
    String keyword = emptyToNull(query.keyword());
    if (keyword != null) {
      filter.add("(owner_code like ? escape '!' or owner_name like ? escape '!')");
      String pattern = "%" + escapeLike(keyword) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    if (query.spuId() != null) {
      filter.add("spu_id = ?");
      binds.add(query.spuId());
    }
    String categoryName = emptyToNull(query.categoryName());
    if (categoryName != null) {
      filter.add("category_name = ?");
      binds.add(categoryName);
    }
    String status = normalizeEnum(query.status());
    if (status != null) {
      filter.add("owner_status = ?");
      binds.add(status);
    }
    String grade = normalizeEnum(query.grade());
    if (grade != null) {
      if (!GRADES.contains(grade)) throw badRequest(gradeProblem(query.grade()));
      filter.add("grade = ?");
      binds.add(grade);
    }
    if (Boolean.TRUE.equals(query.listingReadyOnly())) filter.add("listing_ready");
    if (Boolean.TRUE.equals(query.missingOnly())) filter.add("missing_count > 0");
  }

  /** {@code order by} 子句（列名来自白名单，方向来自布尔参数而不是字符串拼接）。 */
  private String orderClause(CompletenessQuery query) {
    boolean ascending = Boolean.TRUE.equals(query.ascending());
    String direction = ascending ? " asc" : " desc";
    return switch (normalizeOrderBy(query.orderBy())) {
      case "missing" -> "missing_count" + direction + ", owner_code asc";
      case "owner" -> "owner_code" + direction;
      case "updatedAt" -> "updated_at" + direction + " nulls last, owner_code asc";
        // 默认按分数升序（最差的排前面）：治理页先看要补的，而不是先看最好的。
      default -> "score" + direction + ", owner_code asc";
    };
  }

  private CompletenessRow toRow(Record r) {
    BigDecimal score = decimalOf(r.get("score", BigDecimal.class));
    String grade = r.get("grade", String.class);
    return new CompletenessRow(
        r.get("owner_type", String.class),
        r.get("owner_id", UUID.class),
        r.get("spu_id", UUID.class),
        OWNER_SKU.equals(r.get("owner_type", String.class)) ? r.get("owner_id", UUID.class) : null,
        r.get("owner_code", String.class),
        r.get("owner_name", String.class),
        r.get("owner_status", String.class),
        r.get("category_name", String.class),
        score,
        percentValue(score),
        grade,
        gradeLabel(grade),
        longOf(r.get("missing_count", Long.class)),
        longOf(r.get("sku_count", Long.class)),
        Boolean.TRUE.equals(r.get("listing_ready", Boolean.class)),
        Boolean.TRUE.equals(r.get("customs_ready", Boolean.class)));
  }

  // ================================================================ 评分卡

  /**
   * 完整度评分卡（蓝图「输出」那一行的读出口）。
   *
   * <p>检查项逐条列出（含已满足的），所以页面能显示"3/4 已完成"而不是一个孤立的比例； 缺失项另外给一份（{@code missing}），补全清单直接用它。
   */
  public CompletenessScorecard card(String ownerType, UUID ownerId) {
    UUID tenantId = currentTenantId();
    String normalized = normalizeEnum(ownerType);
    if (normalized == null || !OWNER_TYPES.contains(normalized))
      throw badRequest(ownerTypeProblem(ownerType));
    Record row = scoredRow(tenantId, normalized, ownerId);
    if (row == null) throw notFound(normalized + " " + ownerId + " 不存在或不属于当前租户");

    List<CompletenessScoreModels.CompletenessDimension> sortedDims = perSkuDimensions(row);
    BigDecimal score = totalScoreOf(sortedDims);
    String grade = gradeOf(score);

    List<CompletenessScoreModels.CompletenessCheck> checks = checksOf(row, normalized);
    List<CompletenessScoreModels.CompletenessCheck> missing =
        checks.stream().filter(check -> !check.satisfied()).toList();
    List<String> listingBlockers = chainBlockers(LISTING_CHAIN, sortedDims, score, LISTING_GATE);
    List<String> customsBlockers = chainBlockers(CUSTOMS_CHAIN, sortedDims, score, CUSTOMS_GATE);

    return new CompletenessScorecard(
        normalized,
        ownerId,
        row.get("owner_code", String.class),
        row.get("owner_name", String.class),
        row.get("owner_status", String.class),
        row.get("category_name", String.class),
        row.get("category_name", String.class),
        score,
        percentValue(score),
        grade,
        gradeLabel(grade),
        sortedDims,
        sortChecks(checks),
        sortChecks(missing),
        missing.size(),
        longOf(row.get("sku_count", Long.class)),
        listingBlockers.isEmpty(),
        customsBlockers.isEmpty(),
        listingBlockers,
        customsBlockers,
        null,
        "评分现算自当前主数据：属性、图片、报关与认证任何一处改动都会立刻改变分数与分档。"
            + "认证的「无需认证」与「已取得」都算齐全 —— 多数品类本来就不强制认证，"
            + "没有前者的话这些商品会永远缺一条。");
  }

  /** 从一行评分结果里取六个维度的展示记录（就绪判定按链逐维度看，不能只看总分）。 */
  private List<CompletenessScoreModels.CompletenessDimension> perSkuDimensions(Record row) {
    var dims = new ArrayList<CompletenessScoreModels.CompletenessDimension>();
    for (CompletenessScoreRules.Dimension definition : dimensions()) {
      String key = definition.code().toLowerCase(java.util.Locale.ROOT);
      dims.add(
          dimensionScore(
              definition.code(),
              longOf(row.get("c_" + key + "_ok", Long.class)),
              longOf(row.get("c_" + key + "_total", Long.class))));
    }
    return sortDimensions(dims);
  }

  /** 评分卡的一条主体行（找不到返回 null，由调用方转 404）。 */
  private Record scoredRow(UUID tenantId, String ownerType, UUID ownerId) {
    String sqlText =
        ownerScoredSql() + " select * from owner_scored where owner_type = ? and owner_id = ?";
    var binds = tenantBinds(tenantId);
    binds.add(ownerType);
    binds.add(ownerId);
    return sql.fetchOne(sqlText, binds.toArray());
  }

  /**
   * 把一条主体行展开成逐条检查项（含已满足的）。
   *
   * <p>每条缺失都带主体名与下一步动作：「缺 3 条必填属性」没法让人去补， 而「缺 HS 编码 / 申报品名 / 原产国」可以直接点进那三个字段。
   */
  private List<CompletenessScoreModels.CompletenessCheck> checksOf(Record row, String ownerType) {
    var checks = new ArrayList<CompletenessScoreModels.CompletenessCheck>();
    long skuCount = longOf(row.get("sku_count", Long.class));
    checks.add(
        new CompletenessScoreModels.CompletenessCheck(
            MISSING_REQUIRED_ATTRIBUTE,
            "ATTRIBUTE",
            "ATTRIBUTE",
            OWNER_SPU.equals(ownerType) ? "SPU 必填属性" : "该 SKU 的必填属性",
            attrDetail(row),
            attrOk(row) >= attrTotal(row) ? null : missingRemedy(MISSING_REQUIRED_ATTRIBUTE),
            attrOk(row) >= attrTotal(row)));

    boolean hasCategory = Boolean.TRUE.equals(row.get("has_category", Boolean.class));
    checks.add(
        new CompletenessScoreModels.CompletenessCheck(
            MISSING_PRIMARY_CATEGORY,
            "CATEGORY",
            "CATEGORY",
            "主分类",
            hasCategory ? "已挂在主分类下" : missingDetail(MISSING_PRIMARY_CATEGORY, null),
            hasCategory ? null : missingRemedy(MISSING_PRIMARY_CATEGORY),
            hasCategory));

    long ruleTotal = longOf(row.get("rule_total", Long.class));
    long ruleOk = longOf(row.get("rule_ok", Long.class));
    checks.add(
        new CompletenessScoreModels.CompletenessCheck(
            MISSING_CATEGORY_RULE_VALUE,
            "CATEGORY",
            "ATTRIBUTE",
            "类目必填属性",
            ruleTotal == 0 ? "该类目未配置必填属性要求，无规则缺失" : "类目必填属性已填 " + ruleOk + "/" + ruleTotal + " 条",
            ruleOk >= ruleTotal ? null : missingRemedy(MISSING_CATEGORY_RULE_VALUE),
            ruleTotal == 0 || ruleOk >= ruleTotal));

    long imageCount = longOf(row.get("image_count", Long.class));
    long primaryCount = longOf(row.get("primary_count", Long.class));
    long primaryEdge = longOf(row.get("primary_edge", Long.class));
    checks.add(
        new CompletenessScoreModels.CompletenessCheck(
            MISSING_PRIMARY_IMAGE,
            "MEDIA",
            "MEDIA_ASSET",
            "主图",
            primaryCount >= 1
                ? "已有 " + primaryCount + " 张主图"
                : missingDetail(MISSING_PRIMARY_IMAGE, null),
            primaryCount >= 1 ? null : missingRemedy(MISSING_PRIMARY_IMAGE),
            primaryCount >= 1));
    checks.add(
        new CompletenessScoreModels.CompletenessCheck(
            MISSING_IMAGE_COUNT,
            "MEDIA",
            "MEDIA_ASSET",
            "图片数量",
            imageCount >= MIN_IMAGE_COUNT
                ? "图片 " + imageCount + " 张，已达下限"
                : missingDetail(MISSING_IMAGE_COUNT, String.valueOf(imageCount)),
            imageCount >= MIN_IMAGE_COUNT ? null : missingRemedy(MISSING_IMAGE_COUNT),
            imageCount >= MIN_IMAGE_COUNT));
    checks.add(
        new CompletenessScoreModels.CompletenessCheck(
            MISSING_PRIMARY_IMAGE_EDGE,
            "MEDIA",
            "MEDIA_ASSET",
            "主图分辨率",
            primaryEdge >= MIN_PRIMARY_IMAGE_EDGE_PX
                ? "主图最短边 " + primaryEdge + "px，已达标"
                : missingDetail(MISSING_PRIMARY_IMAGE_EDGE, String.valueOf(primaryEdge)),
            primaryEdge >= MIN_PRIMARY_IMAGE_EDGE_PX
                ? null
                : missingRemedy(MISSING_PRIMARY_IMAGE_EDGE),
            primaryEdge >= MIN_PRIMARY_IMAGE_EDGE_PX));

    if (OWNER_SPU.equals(ownerType) && skuCount == 0) {
      // 规则 R4：没有变体时三条 SKU 侧维度各记一条缺失项，而不是把权重摊给别人。
      for (String dimension : List.of("CUSTOMS", "PACKAGING", "CERTIFICATION")) {
        checks.add(
            new CompletenessScoreModels.CompletenessCheck(
                MISSING_SPU_WITHOUT_SKU,
                dimension,
                "SKU",
                "变体",
                missingDetail(MISSING_SPU_WITHOUT_SKU, null),
                missingRemedy(MISSING_SPU_WITHOUT_SKU),
                false));
      }
      return sortChecks(checks);
    }

    appendCustomsChecks(checks, row);
    appendPackagingChecks(checks, row);

    long certOk = longOf(row.get("c_certification_ok", Long.class));
    long certTotal = longOf(row.get("c_certification_total", Long.class));
    String certCode =
        OWNER_SKU.equals(ownerType)
            ? certificationMissingCode(row.get("cert_completeness", String.class))
            : MISSING_CERTIFICATION_NONE;
    checks.add(
        new CompletenessScoreModels.CompletenessCheck(
            certOk >= certTotal ? "CERTIFICATION_OK" : certCode,
            "CERTIFICATION",
            "CERTIFICATION",
            OWNER_SPU.equals(ownerType) ? certTotal + " 个变体的认证" : "认证",
            OWNER_SPU.equals(ownerType)
                ? (certOk >= certTotal
                    ? certTotal + "/" + certTotal + " 个变体认证齐全"
                    : certOk + "/" + certTotal + " 个变体认证齐全")
                : certificationCompletenessLabel(row.get("cert_completeness", String.class)),
            certOk >= certTotal ? null : missingRemedy(certCode),
            certOk >= certTotal));
    return sortChecks(checks);
  }

  private long attrTotal(Record row) {
    return longOf(row.get("c_attribute_total", Long.class));
  }

  private long attrOk(Record row) {
    return longOf(row.get("c_attribute_ok", Long.class));
  }

  private String attrDetail(Record row) {
    long total = attrTotal(row);
    long ok = attrOk(row);
    if (total <= 1 && ok >= 1) return "未定义必填属性，无必填缺失";
    return "必填属性已填 " + ok + "/" + total + " 条";
  }

  private void appendCustomsChecks(
      List<CompletenessScoreModels.CompletenessCheck> checks, Record row) {
    boolean present = Boolean.TRUE.equals(row.get("customs_present", Boolean.class));
    for (String code :
        customsMissingCodes(
            present,
            str(row.get("hs_code", String.class)),
            str(row.get("declared_name", String.class)),
            str(row.get("origin_country", String.class)))) {
      checks.add(
          new CompletenessScoreModels.CompletenessCheck(
              code, "CUSTOMS", "CUSTOMS", "报关属性", customsDetail(code), customsRemedy(code), false));
    }
  }

  private void appendPackagingChecks(
      List<CompletenessScoreModels.CompletenessCheck> checks, Record row) {
    boolean present = Boolean.TRUE.equals(row.get("pkg_present", Boolean.class));
    if (!present) {
      checks.add(
          new CompletenessScoreModels.CompletenessCheck(
              MISSING_PACKAGE_RECORD,
              "PACKAGING",
              "PACKAGE",
              "包装尺寸重量",
              missingDetail(MISSING_PACKAGE_RECORD, null),
              missingRemedy(MISSING_PACKAGE_RECORD),
              false));
      return;
    }
    boolean dimsOk = Boolean.TRUE.equals(row.get("pkg_dims_ok", Boolean.class));
    boolean weightsOk = Boolean.TRUE.equals(row.get("pkg_weights_ok", Boolean.class));
    checks.add(
        new CompletenessScoreModels.CompletenessCheck(
            MISSING_PACKAGE_DIMENSION,
            "PACKAGING",
            "PACKAGE",
            "包装尺寸",
            dimsOk ? "长宽高齐全" : missingDetail(MISSING_PACKAGE_DIMENSION, "长/宽/高"),
            dimsOk ? null : missingRemedy(MISSING_PACKAGE_DIMENSION),
            dimsOk));
    checks.add(
        new CompletenessScoreModels.CompletenessCheck(
            MISSING_PACKAGE_WEIGHT,
            "PACKAGING",
            "PACKAGE",
            "包装重量",
            weightsOk ? "净重与毛重齐全" : missingDetail(MISSING_PACKAGE_WEIGHT, "净重/毛重"),
            weightsOk ? null : missingRemedy(MISSING_PACKAGE_WEIGHT),
            weightsOk));
  }

  // ================================================================ 缺失项清单（批量补全入口）

  /**
   * 缺失项清单：按缺失码聚合，补全工作台的主数据源。
   *
   * <p>每条给出<b>受影响的全部主体数</b>与前若干个样本主体， 让运营看到"全店 137 个 SKU 缺 HS 编码"并能直接跳到第一个去补。
   *
   * <p>排序按 {@code affectedCount} 降序：补全工作要从影响面最大的那条开始 —— 修好一条报关规则可能同时让几十个 SKU 的清关就绪结论翻转。
   */
  public CompletenessGapPage gaps(CompletenessQuery query) {
    UUID tenantId = currentTenantId();
    int page = normalizePage(query.page());
    int size = normalizeSize(query.size());
    var filter = new ArrayList<String>();
    // 只放筛选参数（不含租户）：租户占位符的个数由 SQL 文本数出，
    // 每条语句都重建一遍 binds —— 复用同一个列表一旦中途多/少加过一次，
    // 后面每一条都会报「未设定参数值 N」，而 N 指向的参数恰好是另一个合法值时
    // 报错完全指不到真正的原因。
    var extraBinds = new ArrayList<Object>();
    applyIdentityFilters(query, filter, extraBinds);
    String where = filter.isEmpty() ? "" : " where " + String.join(" and ", filter);

    List<CompletenessGap> all = new ArrayList<>();
    for (CompletenessScoreRules.Dimension dimension : dimensions())
      addDimensionGaps(tenantId, dimension.code(), where, extraBinds, all, query);
    all.sort(
        (a, b) -> {
          int byCount = Long.compare(b.affectedCount(), a.affectedCount());
          if (byCount != 0) return byCount;
          return dimensionOrder().indexOf(a.dimension()) - dimensionOrder().indexOf(b.dimension());
        });

    int from = Math.min(page * size, all.size());
    int to = Math.min(from + size, all.size());
    long affectedOwners = all.stream().mapToLong(CompletenessGap::affectedCount).max().orElse(0L);
    return new CompletenessGapPage(
        new ArrayList<>(all.subList(from, to)),
        all.size(),
        page,
        size,
        affectedOwners,
        dimensionGaps(tenantId, query.ownerType()),
        "缺失项按缺失码聚合：同一条规则在多少个主体上不成立（affectedCount），"
            + "以及前几个代表性主体。补全从影响面最大的那条开始 —— "
            + "一条报关规则修好一次，可能同时让几十个 SKU 的清关就绪结论翻转。"
            + "认证的「已申请未取得」与「已过期待续期」分开列：前者要等审批，后者要发起续期。");
  }

  /** 一个维度下逐个缺失码的受影响主体数。 */
  private void addDimensionGaps(
      UUID tenantId,
      String dimensionCode,
      String where,
      List<Object> extraBinds,
      List<CompletenessGap> sink,
      CompletenessQuery query) {
    String dimensionFilter = normalizeEnum(query.dimension());
    if (dimensionFilter != null && !dimensionFilter.equals(dimensionCode)) return;
    String dimension = dimensionCode;
    String key = dimension.toLowerCase(java.util.Locale.ROOT);
    var conditions = new ArrayList<Map.Entry<String, String>>();
    switch (dimension) {
      case "MEDIA" -> {
        conditions.add(
            Map.entry(
                "c_media_total - (case when c_media_ok >= 1 then 1 else 0 end)",
                MISSING_PRIMARY_IMAGE));
        conditions.add(
            Map.entry(
                "c_media_total - (case when c_media_ok >= 2 then 1 else 0 end)",
                MISSING_IMAGE_COUNT));
        conditions.add(Map.entry("c_media_total - c_media_ok", MISSING_PRIMARY_IMAGE_EDGE));
      }
      case "CUSTOMS" -> {
        // 四条报关检查项各缺一条就各报一次：它们的补全动作不同（建档 vs 补字段）。
        conditions.add(
            Map.entry(
                "4 - (case when customs_present then 1 else 0 end)", MISSING_CUSTOMS_DECLARATION));
        conditions.add(
            Map.entry(
                "4 - (case when btrim(coalesce(hs_code,'')) <> '' then 1 else 0 end)",
                MISSING_HS_CODE));
        conditions.add(
            Map.entry(
                "4 - (case when btrim(coalesce(declared_name,'')) <> '' then 1 else 0 end)",
                MISSING_DECLARED_NAME));
        conditions.add(
            Map.entry(
                "4 - (case when btrim(coalesce(origin_country,'')) <> '' then 1 else 0 end)",
                MISSING_ORIGIN_COUNTRY));
      }
      case "PACKAGING" -> {
        conditions.add(
            Map.entry("3 - (case when pkg_present then 1 else 0 end)", MISSING_PACKAGE_RECORD));
        conditions.add(
            Map.entry(
                "3 - (case when pkg_present and pkg_dims_ok then 1 else 0 end)",
                MISSING_PACKAGE_DIMENSION));
        conditions.add(
            Map.entry(
                "3 - (case when pkg_present and pkg_weights_ok then 1 else 0 end)",
                MISSING_PACKAGE_WEIGHT));
      }
      case "CERTIFICATION" ->
          conditions.add(
              Map.entry("c_certification_total - c_certification_ok", MISSING_CERTIFICATION_NONE));
      case "CATEGORY" -> {
        conditions.add(
            Map.entry(
                "c_category_total - (case when has_category then 1 else 0 end)",
                MISSING_PRIMARY_CATEGORY));
        conditions.add(Map.entry("c_category_total - c_category_ok", MISSING_CATEGORY_RULE_VALUE));
      }
      default ->
          conditions.add(
              Map.entry("c_" + key + "_total - c_" + key + "_ok", MISSING_REQUIRED_ATTRIBUTE));
    }
    for (var condition : conditions) {
      String code = condition.getValue();
      // 两处刻意的写法，都是踩过的坑：
      //
      // 1) **不写 `arr[1:5]`**：jOOQ 收到 SQL 字符串后会解析它再绑定，而它把
      //    数组切片的 `:` 读成了一个绑定标记 —— 渲染出来是 `)[1?]`。
      //    于是 binds 与 ? 的个数明明一样，运行时却报「未设定参数值 N」，
      //    而 N 指向的参数恰好是另一个合法值时报错完全指不到真正的原因。
      //    改成"先 limit 5 再 array_agg"，语义一样，不碰切片语法。
      // 2) **条件只写一遍**：用 hits CTE 收一次，避免把 where 与条件复制两处
      //    —— 复制两处迟早只改一处，而两处的条件一旦不同步，
      //    "受影响主体数"与"样本主体"就会来自两个不同的集合。
      // ownerScoredSql() 以最后一个 CTE 的右括号结尾（不带逗号），这里补上逗号再接 hits。
      String sqlText =
          ownerScoredSql()
              + ", hits as (select owner_code from owner_scored"
              + (where.isEmpty() ? "" : where + " and ")
              + "("
              + condition.getKey()
              + ") > 0) "
              + "select (select count(*) from hits) as affected,"
              + " coalesce((select array_agg(owner_code) from"
              + " (select owner_code from hits order by owner_code limit 5) t), array[]::text[]) as samples";
      var binds = new ArrayList<Object>(tenantBinds(tenantId));
      binds.addAll(extraBinds);
      Record r = sql.fetchOne(sqlText, binds.toArray());
      long affected = longOf(r.get("affected", Long.class));
      if (affected == 0) continue;
      String[] rawSamples = r.get("samples", String[].class);
      {
        List<String> samples = rawSamples == null ? List.of() : List.of(rawSamples);
        sink.add(
            new CompletenessGap(
                code,
                dimension,
                dimensionLabel(dimension),
                missingSubjectType(code),
                CUSTOMS_CHAIN.contains(dimension)
                    ? customsDetail(code)
                    : missingDetail(code, "（按主体统计）"),
                CUSTOMS_CHAIN.contains(dimension) ? customsRemedy(code) : missingRemedy(code),
                affected,
                samples,
                missingNextEntry(code)));
      }
    }
  }

  /** 缺失项清单的统计（补全工作台顶部的进度数字）。 */
  public GapSummary gapSummary(CompletenessQuery query) {
    UUID tenantId = currentTenantId();
    var filter = new ArrayList<String>();
    var binds = tenantBinds(tenantId);
    applyIdentityFilters(query, filter, binds);
    String where = filter.isEmpty() ? "" : " where " + String.join(" and ", filter);
    String sqlText =
        ownerScoredSql()
            + " select count(*) as owners,"
            + " coalesce(sum(missing_count), 0) as missing,"
            + " count(*) filter (where missing_count = 0) as clean"
            + " from owner_scored"
            + where;
    Record r = sql.fetchOne(sqlText, binds.toArray());

    return new GapSummary(
        longOf(r.get("owners", Long.class)),
        longOf(r.get("missing", Long.class)),
        dimensionGaps(tenantId, query.ownerType()),
        gradeCounts(tenantId, query.ownerType()),
        longOf(r.get("clean", Long.class)),
        "缺失项数是 Σ(每个维度的检查项数 - 已满足数)，一条缺失项对应一个明确的下一步动作。" + "cleanCount 是零缺失的主体数 —— 它是补全工作的进度条。");
  }

  // ================================================================ 评估快照（批量补全入口）

  /**
   * 批量评估并落快照（蓝图的「批量补全入口」）。
   *
   * <p>调用方只传<b>主体 id</b>，分数由服务端现算（规则 R1）： 一个"把分数传进来让我存一下"的端点等于给完整度发了一张可自填的通行证， 而完整度直接决定能否刊登与能否清关。
   *
   * <p>逐条成败都带出来，<b>不做"整体成功/失败"</b>：批量补全天然是"好的补了、 还差的留着"，一个整体 500 会让人以为一条都没补上。
   */
  public CompletenessEvaluationResult evaluate(
      AccessService.Access actor, CompletenessEvaluationInput input) {
    currentTenantId();
    String ownerType = normalizeEnum(input.ownerType());
    if (ownerType == null || !OWNER_TYPES.contains(ownerType))
      throw badRequest(ownerTypeProblem(input.ownerType()));
    if (input.ownerIds() == null || input.ownerIds().isEmpty()) throw badRequest("请至少选择一个待评估的主体");
    if (input.ownerIds().size() > 500) throw badRequest("一次最多评估 500 个主体，请分批提交");

    var evaluations = new ArrayList<CompletenessEvaluation>();
    var failures = new ArrayList<EvaluationFailure>();
    for (UUID ownerId : input.ownerIds()) {
      try {
        evaluations.add(storeEvaluation(actor, ownerType, ownerId, input.note()));
      } catch (ResponseStatusException e) {
        failures.add(
            new EvaluationFailure(ownerId, e.getReason() == null ? "主体不存在" : e.getReason()));
      }
    }
    return new CompletenessEvaluationResult(
        input.ownerIds().size(),
        evaluations.size(),
        failures.size(),
        evaluations,
        failures,
        "快照只回答「过去某一刻的分数与缺失项」，读出口一律现算 —— " + "删掉全部快照不影响评分功能。跨租户或不存在的主体按条失败返回，不会中断整批。");
  }

  private CompletenessEvaluation storeEvaluation(
      AccessService.Access actor, String ownerType, UUID ownerId, String note) {
    UUID tenantId = currentTenantId();
    CompletenessScorecard card = card(ownerType, ownerId);
    UUID id = Ids.next();
    var dimensionGaps = new ArrayList<DimensionGap>();
    for (CompletenessScoreModels.CompletenessDimension dim : card.dimensions())
      dimensionGaps.add(
          new DimensionGap(dim.dimension(), dim.dimensionLabel(), dim.total() - dim.satisfied()));
    sql.execute(
        """
        insert into completeness_evaluation(
          id, tenant_id, owner_type, spu_id, sku_id, score, grade,
          listing_ready, customs_ready, dimension_scores, missing_checks, missing_count,
          evaluated_by, note, version)
        values(?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,0)
        """,
        id,
        tenantId,
        ownerType,
        OWNER_SPU.equals(ownerType) ? ownerId : null,
        OWNER_SKU.equals(ownerType) ? ownerId : null,
        card.score(),
        card.grade(),
        card.listingReady(),
        card.customsReady(),
        writeJson(dimensionGaps),
        writeJson(card.missing()),
        card.missing().size(),
        actor.userId(),
        note == null ? "" : note.trim());
    // 走 EVALUATION_SELECT 而不是 select *：toEvaluation 要读 owner_code/owner_name，
    // 那两列是 join 出来的，裸 select * 的行类型里没有它们（jOOQ 会报
    // "field owner_code is not contained in row type"）。
    return toEvaluation(
        sql.fetchOne(EVALUATION_SELECT + " where e.tenant_id=? and e.id=?", tenantId, id));
  }

  /** 评估快照列表。 */
  public CompletenessEvaluationPage evaluations(
      UUID ownerId, String ownerType, int page, int size) {
    UUID tenantId = currentTenantId();
    int p = normalizePage(page);
    int s = normalizeSize(size);
    var binds = new ArrayList<Object>();
    var clause = new ArrayList<String>();
    if (ownerId != null) {
      clause.add("(e.spu_id = ? or e.sku_id = ?)");
      binds.add(ownerId);
      binds.add(ownerId);
    }
    String normalized = normalizeEnum(ownerType);
    if (normalized != null) {
      clause.add("e.owner_type = ?");
      binds.add(normalized);
    }
    String extra = clause.isEmpty() ? "" : " and " + String.join(" and ", clause);
    long total =
        sql.fetchOne(
                "select count(*) from completeness_evaluation e where e.tenant_id=?" + extra,
                concatTenant(tenantId, binds))
            .get(0, Long.class);
    var items = new ArrayList<CompletenessEvaluation>();
    for (Record r :
        sql.fetch(
            EVALUATION_SELECT
                + " where e.tenant_id=?"
                + extra
                + " order by e.evaluated_at desc, e.id limit ? offset ?",
            concatTenant(tenantId, binds, s, p * s))) items.add(toEvaluation(r));
    return new CompletenessEvaluationPage(
        items,
        total,
        p,
        s,
        "评估历史回答「上周三拦住这批刊登的那个分数是几分、缺哪几项」—— 现算的评分答不了过去。"
            + "分数与分档都是<b>当时</b>的样子：分档阈值以后可能被调整，"
            + "而历史快照必须保持它写入时的结论，否则改一次阈值所有历史分档都跟着变。");
  }

  /** 一条评估快照。 */
  public CompletenessEvaluation evaluation(UUID id) {
    UUID tenantId = currentTenantId();
    Record r = sql.fetchOne(EVALUATION_SELECT + " where e.tenant_id=? and e.id=?", tenantId, id);
    if (r == null) throw notFound("评估快照不存在");
    return toEvaluation(r);
  }

  /** 评估快照的公共 SELECT（列表与详情共用一份，避免两处各写一次然后改一处漏一处）。 */
  private static final String EVALUATION_SELECT =
      "select e.id, e.tenant_id, e.owner_type, e.spu_id, e.sku_id, e.score, e.grade,"
          + " e.listing_ready, e.customs_ready, e.dimension_scores, e.missing_checks,"
          + " e.missing_count, e.evaluated_by, e.evaluated_at, e.note, e.version,"
          + " case when e.owner_type='SPU' then sp.code else k.code end as owner_code,"
          + " case when e.owner_type='SPU' then sp.name else k.name end as owner_name"
          + " from completeness_evaluation e"
          + " left join spu sp on sp.tenant_id=e.tenant_id and sp.id=e.spu_id"
          + " left join sku k on k.tenant_id=e.tenant_id and k.id=e.sku_id";

  private CompletenessEvaluation toEvaluation(Record r) {
    List<DimensionGap> gaps = new ArrayList<>();
    String rawGaps = jsonText(r, "dimension_scores");
    if (rawGaps != null && !rawGaps.isBlank())
      for (var node : readJson(rawGaps))
        gaps.add(
            new DimensionGap(
                node.path("dimension").asText(),
                node.path("dimensionLabel").asText(),
                node.path("missingCount").asLong()));
    List<CompletenessScoreModels.CompletenessCheck> missing = new ArrayList<>();
    String rawMissing = jsonText(r, "missing_checks");
    if (rawMissing != null && !rawMissing.isBlank())
      for (var node : readJson(rawMissing))
        missing.add(
            new CompletenessScoreModels.CompletenessCheck(
                node.path("code").asText(),
                node.path("dimension").asText(),
                node.path("subjectType").asText(),
                node.path("subjectLabel").asText(),
                node.path("detail").asText(),
                node.path("remedy").asText(),
                false));
    BigDecimal score = decimalOf(r.get("score", BigDecimal.class));
    return new CompletenessEvaluation(
        r.get("id", UUID.class),
        r.get("owner_type", String.class),
        OWNER_SPU.equals(r.get("owner_type", String.class))
            ? r.get("spu_id", UUID.class)
            : r.get("sku_id", UUID.class),
        r.get("owner_code", String.class),
        r.get("owner_name", String.class),
        score,
        r.get("grade", String.class),
        gradeLabel(r.get("grade", String.class)),
        Boolean.TRUE.equals(r.get("listing_ready", Boolean.class)),
        Boolean.TRUE.equals(r.get("customs_ready", Boolean.class)),
        longOf(r.get("missing_count", Long.class)),
        gaps,
        missing,
        r.get("evaluated_by", UUID.class),
        instant(r.get("evaluated_at", OffsetDateTime.class)),
        r.get("note", String.class),
        longOf(r.get("version", Long.class)));
  }

  /**
   * jsonb 列取原文。
   *
   * <p>刻意<b>不</b>只判 {@code instanceof String}：PG 的 jsonb 会被驱动交回成 {@code PGobject} 而不是 {@code
   * String}，只认 String 的那个分支会静默跳过， 症状是"快照里没有缺失项"而库里明明存了 —— 一个只看现象根本查不出来的 bug。 {@code
   * PGobject.toString()} 就是它的 JSON 原文，所以两种类型都能拿到。
   */
  private static String jsonText(Record r, String column) {
    Object value = r.get(column);
    if (value == null) return null;
    if (value instanceof String text) return text;
    return value.toString();
  }

  // ================================================================ 预检出口（供 4.2.3.2）

  /** 刊登预检（供下游 4.2.3.2 调用）。 */
  public ListingPrecheck listingPrecheck(List<UUID> spuIds) {
    var blocked = new ArrayList<CompletenessRow>();
    var blockers = new ArrayList<CompletenessScoreRules.PrecheckBlocker>();
    int checked = 0;
    for (UUID ownerId : spuIds == null ? List.<UUID>of() : spuIds) {
      checked++;
      CompletenessRow row = precheckRow(OWNER_SPU, ownerId);
      if (row != null && !row.listingReady()) {
        blocked.add(row);
        blockers.add(precheckBlocker(row));
      }
    }
    return new ListingPrecheck(
        blockers.isEmpty(),
        checked,
        blockers.size(),
        blocked.stream().map(CompletenessRow::ownerCode).toList(),
        blockers,
        "刊登预检：给一组 SPU 判定能否刊登。规则 R3 —— 必须总分 ≥ "
            + LISTING_GATE.toPlainString()
            + " 且必填属性/图片/类目必填/认证四个维度都无缺失。"
            + "报 blockedOwners + blockers 而不是只给一个布尔：布尔答不出「这 3 个 SPU 为什么被拦住」，"
            + "而预检页要把缺失项直接指到字段上。");
  }

  /** 清关预检（供报关流程调用）。 */
  public CustomsPrecheck customsPrecheck(List<UUID> skuIds) {
    var blocked = new ArrayList<CompletenessRow>();
    var blockers = new ArrayList<CompletenessScoreRules.PrecheckBlocker>();
    int checked = 0;
    for (UUID ownerId : skuIds == null ? List.<UUID>of() : skuIds) {
      checked++;
      CompletenessRow row = precheckRow(OWNER_SKU, ownerId);
      if (row != null && !row.customsReady()) {
        blocked.add(row);
        blockers.add(precheckBlocker(row));
      }
    }
    return new CustomsPrecheck(
        blockers.isEmpty(),
        checked,
        blockers.size(),
        blocked.stream().map(CompletenessRow::ownerCode).toList(),
        blockers,
        "清关预检：给一组 SKU 判定能否报关。规则 R3 —— 必须总分 ≥ "
            + CUSTOMS_GATE.toPlainString()
            + " 且报关属性与包装尺寸重量两个维度都无缺失。");
  }

  private CompletenessRow precheckRow(String ownerType, UUID ownerId) {
    UUID tenantId = currentTenantId();
    String sqlText =
        ownerScoredSql()
            + " select owner_type, tenant_id, spu_id, owner_id, owner_code, owner_name, owner_status,"
            + " category_name, score, grade, missing_count, sku_count, listing_ready, customs_ready"
            + " from owner_scored where owner_type = ? and owner_id = ?";
    var binds = tenantBinds(tenantId);
    binds.add(ownerType);
    binds.add(ownerId);
    Record row = sql.fetchOne(sqlText, binds.toArray());
    return row == null ? null : toRow(row);
  }

  private CompletenessScoreRules.PrecheckBlocker precheckBlocker(CompletenessRow row) {
    CompletenessScorecard card = card(row.ownerType(), row.ownerId());
    return new CompletenessScoreRules.PrecheckBlocker(
        card.ownerType(),
        card.ownerId(),
        card.ownerCode(),
        card.ownerName(),
        card.score(),
        card.grade(),
        card.missing().stream()
            .map(CompletenessScoreModels.CompletenessCheck::code)
            .distinct()
            .toList(),
        card.listingReady() ? card.customsBlockers() : card.listingBlockers(),
        "评分现算自当前主数据；补齐下列缺失项后重新预检即可放行。");
  }

  // ================================================================ 报关属性（catalog 侧声明数据）

  /** 读某个 SKU 的报关属性（无行返回 null，评分据此报「尚未登记」）。 */
  public CustomsDeclaration customsOf(UUID skuId) {
    UUID tenantId = currentTenantId();
    Record r =
        sql.fetchOne(
            "select d.*, k.code as sku_code, k.name as sku_name from sku_customs_declaration d"
                + " join sku k on k.tenant_id = d.tenant_id and k.id = d.sku_id"
                + " where d.tenant_id = ? and d.sku_id = ?",
            tenantId,
            skuId);
    return r == null ? null : toCustoms(r);
  }

  private CustomsDeclaration toCustoms(Record r) {
    List<String> elements = new ArrayList<>();
    Object raw = r.get("declaration_elements");
    if (raw instanceof String[] array) elements.addAll(Arrays.asList(array));
    return new CustomsDeclaration(
        r.get("id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        str(r.get("hs_code", String.class)),
        str(r.get("declared_name", String.class)),
        str(r.get("origin_country", String.class)),
        str(r.get("legal_unit", String.class)),
        elements,
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        longOf(r.get("version", Long.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 写报关属性（新建或整体替换，一个 SKU 一份）。
   *
   * <p>刻意<b>不</b>要求 HS 编码与申报品名必填（V37 第二节）：报关属性是逐步补全的， 建档时常常只有品名、HS 编码要等供应商确认。要求全填会让"建一行"都做不了，
   * 于是干脆不建 —— 那样连"缺了哪几项"都看不到。必填与否由评分判。
   */
  public CustomsDeclaration saveCustoms(
      AccessService.Access actor, UUID skuId, CustomsDeclarationInput input) {
    UUID tenantId = currentTenantId();
    requireSku(tenantId, skuId);
    String hsCode = normalizeHsCode(input.hsCode());
    String declaredName = emptyToNull(input.declaredName());
    String originCountry = normalizeCountry(input.originCountry());
    String legalUnit = emptyToNull(input.legalUnit());
    List<String> elements = cleanElements(input.declarationElements());
    boolean enabled = normalizeFlag(input.enabled());
    String problem = customsProblem(hsCode, declaredName, originCountry, legalUnit, elements);
    if (problem != null) throw badRequest(problem);
    if (!customsHasContent(
        hsCode, declaredName, originCountry, legalUnit, elements, input.enabled()))
      throw badRequest("报关属性至少要填一项：全空的报关行在评分里与「未登记」完全一样，只会多一条要删的脏数据");
    long version = requireVersion(input.version());

    Record existing =
        sql.fetchOne(
            "select id, version from sku_customs_declaration where tenant_id=? and sku_id=?",
            tenantId,
            skuId);
    if (existing == null) {
      sql.execute(
          """
          insert into sku_customs_declaration(
            id, tenant_id, sku_id, hs_code, declared_name, origin_country, legal_unit,
            declaration_elements, enabled, version)
          values(?,?,?,?,?,?,?,?::text[],?,0)
          """,
          Ids.next(),
          tenantId,
          skuId,
          hsCode == null ? "" : hsCode,
          declaredName == null ? "" : declaredName,
          originCountry == null ? "" : originCountry,
          legalUnit == null ? "" : legalUnit,
          elements.toArray(new String[0]),
          enabled);
      audit(actor, skuId, "CUSTOMS_DECLARATION_CREATE");
    } else {
      int changed =
          sql.execute(
              """
          update sku_customs_declaration set hs_code=?, declared_name=?, origin_country=?,
            legal_unit=?, declaration_elements=?::text[], enabled=?, version=version+1, updated_at=now()
          where tenant_id=? and sku_id=? and version=?
          """,
              hsCode == null ? "" : hsCode,
              declaredName == null ? "" : declaredName,
              originCountry == null ? "" : originCountry,
              legalUnit == null ? "" : legalUnit,
              elements.toArray(new String[0]),
              enabled,
              tenantId,
              skuId,
              version);
      if (changed == 0) throw conflict("报关属性已被他人修改，请刷新后重试");
      audit(actor, skuId, "CUSTOMS_DECLARATION_UPDATE");
    }
    return customsOf(skuId);
  }

  /** 删报关属性：删掉之后评分退回「尚未登记」（一个有意义且可逆的状态）。 */
  public void deleteCustoms(AccessService.Access actor, UUID skuId, long version) {
    UUID tenantId = currentTenantId();
    requireSku(tenantId, skuId);
    int changed =
        sql.execute(
            "delete from sku_customs_declaration where tenant_id=? and sku_id=? and version=?",
            tenantId,
            skuId,
            version);
    if (changed == 0) throw conflict("报关属性不存在或已被他人修改，请刷新后重试");
    audit(actor, skuId, "CUSTOMS_DECLARATION_DELETE");
  }

  private List<String> cleanElements(List<String> elements) {
    var cleaned = new ArrayList<String>();
    if (elements != null)
      for (String element : elements) {
        String value = emptyToNull(element);
        if (value != null) cleaned.add(value);
      }
    return cleaned;
  }

  // ================================================================ 认证（catalog 侧声明数据）

  /** 某个 SKU 的认证集合 + 四态结论（供档案页与评分卡共用同一份判定口径）。 */
  public CertificationSet certifications(UUID skuId) {
    UUID tenantId = currentTenantId();
    requireSku(tenantId, skuId);
    var items = new ArrayList<Certification>();
    for (Record r :
        sql.fetch(
            "select c.*, k.code as sku_code from sku_certification c"
                + " join sku k on k.tenant_id = c.tenant_id and k.id = c.sku_id"
                + " where c.tenant_id = ? and c.sku_id = ? order by c.certification_code",
            tenantId,
            skuId)) items.add(toCertification(r));
    String completeness = certCompleteness(tenantId, skuId);
    long expired =
        sql.fetchOne(
                "select count(*) from sku_certification where tenant_id=? and sku_id=? and scored"
                    + " and status='HELD' and expires_on is not null and expires_on < current_date",
                tenantId,
                skuId)
            .get(0, Long.class);
    return new CertificationSet(
        skuId,
        items,
        completeness,
        certificationCompletenessLabel(completeness),
        expired,
        "四态判定（规则 R5）：有「无需认证」或有一张未过期的「已取得」都算齐全。"
            + "「已申请未取得」与「已过期待续期」分开列 —— 前者要等审批、后者要发起续期，"
            + "把两者合成一条「认证缺失」会让人反复去填一个填不了的字段。"
            + "标了「不计入评分」的行仍留在档案里可查，但不参与完整度计算。");
  }

  /**
   * 认证四态（规则 R5 的读出口，与评分 SQL 的聚合同一口径）。
   *
   * <p>刻意与 {@code sku_cert} 的 CASE 逐条同序，两处各写一遍的症状是 「评分卡说已齐全、认证档案说待续期」，而它们读的是同一张表。
   */
  private String certCompleteness(UUID tenantId, UUID skuId) {
    Record r =
        sql.fetchOne(
            """
        select case
            when count(*) = 0 then 'NONE'
            when bool_or(status='NOT_REQUIRED') then 'COMPLETE'
            when bool_or(status='HELD' and (expires_on is null or expires_on >= current_date)) then 'COMPLETE'
            when bool_or(status='EXPIRED'
                  or (status='HELD' and expires_on is not null and expires_on < current_date)) then 'EXPIRED'
            when bool_or(status='PENDING') then 'PENDING'
            else 'NONE' end as completeness
        from sku_certification where tenant_id=? and sku_id=? and scored
        """,
            tenantId,
            skuId);
    return r == null || r.get("completeness", String.class) == null
        ? "NONE"
        : r.get("completeness", String.class);
  }

  private Certification toCertification(Record r) {
    return new Certification(
        r.get("id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("certification_code", String.class),
        r.get("certification_name", String.class),
        r.get("status", String.class),
        r.get("certificate_no", String.class),
        toLocalDate(r.get("issued_on", LocalDate.class)),
        toLocalDate(r.get("expires_on", LocalDate.class)),
        Boolean.TRUE.equals(r.get("scored", Boolean.class)),
        r.get("note", String.class),
        longOf(r.get("version", Long.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 新建（{@code id == null}）或改一条认证声明。 */
  public Certification saveCertification(
      AccessService.Access actor, UUID skuId, UUID id, CertificationInput input) {
    UUID tenantId = currentTenantId();
    requireSku(tenantId, skuId);
    String code = normalizeEnum(input.certificationCode());
    String name = emptyToNull(input.certificationName());
    String status = normalizeEnum(input.status());
    String certificateNo = emptyToNull(input.certificateNo());
    String note = emptyToNull(input.note());
    boolean scored = normalizeFlag(input.scored());
    String problem =
        certificationProblem(
            code,
            name,
            status,
            certificateNo,
            input.issuedOn(),
            input.expiresOn(),
            input.scored(),
            input.note());
    if (problem != null) throw badRequest(problem);

    if (id == null) {
      UUID newId = Ids.next();
      sql.execute(
          """
          insert into sku_certification(id, tenant_id, sku_id, certification_code, certification_name,
            status, certificate_no, issued_on, expires_on, scored, note, version)
          values(?,?,?,?,?,?,?,?,?,?,?,0)
          """,
          newId,
          tenantId,
          skuId,
          code,
          name == null ? "" : name,
          status,
          certificateNo == null ? "" : certificateNo,
          input.issuedOn(),
          input.expiresOn(),
          scored,
          note == null ? "" : note);
      audit(actor, skuId, "CERTIFICATION_CREATE");
      return oneCertification(tenantId, newId);
    }
    long version = requireVersion(input.version());
    int changed =
        sql.execute(
            """
        update sku_certification set certification_code=?, certification_name=?, status=?,
          certificate_no=?, issued_on=?, expires_on=?, scored=?, note=?, version=version+1, updated_at=now()
        where tenant_id=? and id=? and version=?
        """,
            code,
            name == null ? "" : name,
            status,
            certificateNo == null ? "" : certificateNo,
            input.issuedOn(),
            input.expiresOn(),
            scored,
            note == null ? "" : note,
            tenantId,
            id,
            version);
    if (changed == 0) throw conflict("认证记录已被他人修改，请刷新后重试");
    audit(actor, skuId, "CERTIFICATION_UPDATE");
    return oneCertification(tenantId, id);
  }

  private Certification oneCertification(UUID tenantId, UUID id) {
    Record r =
        sql.fetchOne(
            "select c.*, k.code as sku_code from sku_certification c"
                + " join sku k on k.tenant_id = c.tenant_id and k.id = c.sku_id"
                + " where c.tenant_id = ? and c.id = ?",
            tenantId,
            id);
    if (r == null) throw notFound("认证记录不存在");
    return toCertification(r);
  }

  /** 删一条认证：删掉之后评分退回该维度的真实结论。 */
  public void deleteCertification(AccessService.Access actor, UUID id, long version) {
    UUID tenantId = currentTenantId();
    int changed =
        sql.execute(
            "delete from sku_certification where tenant_id=? and id=? and version=?",
            tenantId,
            id,
            version);
    if (changed == 0) throw conflict("认证记录不存在或已被他人修改，请刷新后重试");
    audit(actor, id, "CERTIFICATION_DELETE");
  }

  // ================================================================ 辅助

  private void requireSku(UUID tenantId, UUID skuId) {
    if (sql.fetchOne("select 1 from sku where tenant_id=? and id=?", tenantId, skuId) == null)
      throw notFound("SKU 不存在或不属于当前租户");
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  /** 租户来自 RLS 生效的连接上下文（由 AccessService 绑定），<b>不读 X-Tenant-Id 头</b>。 */
  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static Object[] concatTenant(UUID tenantId, List<Object> rest, Object... tail) {
    var all = new ArrayList<Object>();
    all.add(tenantId);
    all.addAll(rest);
    all.addAll(Arrays.asList(tail));
    return all.toArray();
  }

  private static long longOf(Long value) {
    return value == null ? 0L : value;
  }

  private static BigDecimal decimalOf(BigDecimal value) {
    return value == null
        ? BigDecimal.ZERO.setScale(SCORE_SCALE, java.math.RoundingMode.HALF_UP)
        : value.setScale(SCORE_SCALE, java.math.RoundingMode.HALF_UP);
  }

  private static String str(String value) {
    return value == null ? "" : value;
  }

  private static LocalDate toLocalDate(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDate localDate) return localDate;
    if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
    return null;
  }

  /** {@code timestamptz} 读出口径：统一转成 UTC 的 ISO-8601 串（带 {@code Z}），不交给前端猜时区。 */
  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  /** jsonb 读出：PG 的 jsonb 列由 jOOQ 交回来可能是 {@code String} 或已解析对象。 */
  private static com.fasterxml.jackson.databind.JsonNode readJson(String text) {
    try {
      return JSON.readTree(text);
    } catch (Exception e) {
      throw new IllegalStateException("完整度快照反序列化失败", e);
    }
  }

  private static String writeJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("完整度快照序列化失败", e);
    }
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
