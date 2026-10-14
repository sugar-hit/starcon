package com.starcon.catalog;

import static com.starcon.catalog.CustomsDestinationVersionModels.*;

import com.starcon.catalog.CustomsAttributeModels.CustomsProfile;
import com.starcon.catalog.CustomsAttributeModels.DeclarationReady;
import com.starcon.tenancy.Ids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.7.1.2 目的地报关版本：按目的地版本化维护申报差异、发布状态机、 按时刻查生效版本，以及<b>把基础属性与目的地覆盖合成成一份可直接申报的资料</b>。
 *
 * <p>蓝图原文：「维护同一 SKU 在不同目的地国家的报关差异（不同税号、不同申报品名、 不同认证要求），按目的地版本化。」「输出：按目的地查询的有效报关版本。」
 * 「<b>不同目的地必须用不同版本，不能用一套申报资料打通全球</b>。」
 *
 * <p><b>本模块存在的理由是最后那句</b>。它在本服务里落成三条硬边界：
 *
 * <ol>
 *   <li><b>目的国不可通配</b>：{@code '*'} 在归一化阶段（{@link #profile} 起的每个入口） 与 DB 层（CHECK + 触发器）各拒一次。见 {@link
 *       CustomsDestinationVersionRules}。
 *   <li><b>没有生效版本就报错</b>：{@link #effective} / {@link #requirements} / {@link #resolve}
 *       在该目的地无生效版本时一律 <b>409</b>， <b>绝不静默回退到基础属性</b> —— 回退等于让运营漏维护的目的地被基础属性接管，
 *       于是「用一套申报资料打通全球」又悄悄回来了，只是换了个更隐蔽的入口。
 *   <li><b>发布是第三个权限</b>（{@code catalog.compliance.publish}）： 能编辑目的地版本的人不一定有权把它发布成下游要用的那一份。 理由与
 *       3.7.1.1 的 review 权限分开同理：填 HS 码是日常录入， 而「让这一版成为 10 报关清关时引用的那一份」是另一回事。
 * </ol>
 *
 * <p><b>覆盖 vs 继承的唯一实现</b>在 {@link CustomsDestinationVersionRules#merge}：
 * 目的地版本覆盖税号与品名两格，法定计量单位与申报要素只来自基础属性， 享惠要「有资格」与「该目的地承认」两者都满足。
 *
 * <p><b>不落派生值</b>（V95 第二节）：合成后的申报资料与关税成本一律现算。 完税价格取决于成交价与汇率，那是 10 报关与 12 财务的事实。
 *
 * <p><b>权限口径</b>：读 {@code catalog.compliance.read}、写 {@code catalog.compliance.update}、 <b>发布
 * {@code catalog.compliance.publish}</b>。
 */
@Service
public class CustomsDestinationVersionService {
  /** 一次保存的认证要求条数上限：一个目的地顶多十来个认证，多出来基本是手滑。 */
  static final int MAX_REQUIREMENTS = 50;

  private final DSLContext sql;
  private final CustomsAttributeService customs;

  public CustomsDestinationVersionService(DSLContext sql, CustomsAttributeService customs) {
    this.sql = sql;
    this.customs = customs;
  }

  // ============================================================ 读取

  /**
   * 一个 SKU 在一个目的地的版本面板（含草稿与已发布，标出此刻生效的那个）。
   *
   * <p><b>{@code effectiveVersionId} 为 null 是一个真实状态而不是错误</b>：
   * 该目的地此刻可能只有草稿、或者已发布版本的区间都还没到。界面必须显式说这件事， 不能让它看起来像「这个目的地不需要差异声明」。
   *
   * <p>基础属性摘要（{@code base}）内联返回：只列差异列而不列基础值的话， 运营无法判断「这个格子空着」是「沿用基础」还是「忘了填」—— 而那正是本模块最隐蔽的一种漏维护。
   */
  public DestinationVersionProfile profile(UUID skuId, String destination) {
    String code = rule(() -> CustomsDestinationVersionRules.normalizeDestination(destination));
    // 先取 SKU 行：不存在就 404（RLS 已过滤，租户看不到与不存在不区分），
    // 顺带把租户拿到手，后面每条 SQL 都以它为第一段绑定。
    UUID tenantId = skuRow(skuId).get("tenant_id", UUID.class);
    CustomsProfile base = customs.profile(skuId);

    List<Record> rows =
        sql.fetch(
            "select id,version_no,destination_country_code,destination_hs_code,"
                + "destination_declared_name,preferential_applicable,status,published_by,published_at,"
                + "effective_from,effective_to,note,version,updated_at"
                + " from customs_destination_version"
                + " where tenant_id=? and sku_id=? and destination_country_code=?"
                + " order by version_no desc",
            tenantId,
            skuId,
            code);

    Instant now = Instant.now();
    Record effective = effectiveRow(tenantId, skuId, code, now);
    UUID effectiveId = effective == null ? null : effective.get("id", UUID.class);

    Map<UUID, List<DestinationRequirement>> requirementsByVersion =
        requirementsByVersion(tenantId, rows.stream().map(r -> r.get("id", UUID.class)).toList());

    List<DestinationVersionRow> versions = new ArrayList<>(rows.size());
    int drafts = 0;
    String latestUpdate = null;
    for (Record r : rows) {
      UUID id = r.get("id", UUID.class);
      DestinationVersionRow row = toVersionRow(r, effectiveId, requirementsByVersion.get(id));
      if (row.status().equals(CustomsDestinationVersionRules.DRAFT)) drafts++;
      if (latestUpdate == null || row.updatedAt().compareTo(latestUpdate) > 0)
        latestUpdate = row.updatedAt();
      versions.add(row);
    }

    return new DestinationVersionProfile(
        base.skuId(),
        base.skuCode(),
        base.skuName(),
        base.spuCode(),
        code,
        baseOf(base),
        versions,
        effectiveId,
        drafts,
        latestUpdate);
  }

  /** 3.7.1.1 基础属性的五格摘要（{@link BaseAttribute}）。 */
  private static BaseAttribute baseOf(CustomsProfile base) {
    return new BaseAttribute(
        base.attributeId(),
        base.hsCode(),
        base.declaredName(),
        base.statutoryUnitCode(),
        base.status(),
        CustomsAttributeRules.APPROVED.equals(base.status()));
  }

  /**
   * 规则 R4：某时刻的生效版本。
   *
   * <p><b>没有生效版本一律 409</b>，而不是回退到基础属性（类注释第 2 条）。 半开区间 {@code [from, to)} 的比较写成 {@code from <= t AND
   * (to IS NULL OR t < to)}， 与 {@link CustomsDestinationVersionRules#covers} 同一语义； 重叠由 DB
   * 触发器保证（R2），所以这里至多命中一行 —— {@code limit 1} 是防御不是裁决。
   */
  public EffectiveDestinationVersion effective(UUID skuId, String destination, String at) {
    String code = rule(() -> CustomsDestinationVersionRules.normalizeDestination(destination));
    UUID tenantId = skuTenantId(skuId);
    Instant moment = momentOf(at);
    Record row = effectiveRow(tenantId, skuId, code, moment);
    if (row == null) throw noEffectiveVersion(code, skuId, moment);

    UUID versionId = row.get("id", UUID.class);
    return new EffectiveDestinationVersion(
        skuId,
        code,
        versionId,
        row.get("version_no", Integer.class),
        moment.toString(),
        row.get("destination_hs_code", String.class),
        row.get("destination_declared_name", String.class),
        Boolean.TRUE.equals(row.get("preferential_applicable", Boolean.class)),
        row.get("published_by", UUID.class),
        instant(row.get("published_at", OffsetDateTime.class)),
        requirementsByVersion(tenantId, List.of(versionId)).getOrDefault(versionId, List.of()));
  }

  /**
   * 认证要求清单（供 16 合规的认证台账）。
   *
   * <p><b>同样在无生效版本时报错</b>：返回一个空列表会被 16 合规读成 「这个目的地不需要任何认证」，而真实原因可能是「这个目的地还没维护」。 这两件事的下一步动作完全不同（去维护
   * vs 去做认证），返回空列表就把它们塌缩了。
   */
  public DestinationRequirementList requirements(UUID skuId, String destination, String at) {
    EffectiveDestinationVersion effective = effective(skuId, destination, at);
    int requiredOnly =
        (int) effective.requirements().stream().filter(DestinationRequirement::required).count();
    return new DestinationRequirementList(
        skuId,
        effective.destinationCountryCode(),
        effective.destinationVersionId(),
        effective.versionNo(),
        effective.effectiveAt(),
        effective.requirements(),
        requiredOnly);
  }

  /**
   * <b>供 10 报关调用</b>：把基础属性（3.7.1.1）与目的地覆盖（3.7.1.2） 合成成一份可直接申报的资料 —— 本模块对下游的核心价值。
   *
   * <p><b>两道闸门都不过就报错</b>，顺序有讲究：
   *
   * <ol>
   *   <li>先取基础申报资料（{@link CustomsAttributeService#declarationReady}）： 基础属性未审核通过时它自己就抛 409，合成无从谈起。
   *   <li>再取该目的地的生效版本：没有就抛 409，<b>不回退到基础</b>。
   * </ol>
   *
   * <p>顺序反过来（先查目的地再取基础）会让「基础没过审」这个更根本的原因 被「该目的地没维护」盖住，而运营补完目的地版本后仍然过不去，得再试一次。 先报更根本的那一条，一轮就能改完。
   *
   * <p>返回体字段与 V85 {@code customs_declaration_line} 逐列对应（见 {@link ResolvedDeclaration}
   * 的类注释），申报货值不在本端点的裁决范围内。
   */
  public ResolvedDeclaration resolve(UUID skuId, String destination, String at) {
    String code = rule(() -> CustomsDestinationVersionRules.normalizeDestination(destination));
    // 闸门一：基础申报资料（未审核通过时 declarationReady 自己抛 409）。
    DeclarationReady base = customs.declarationReady(skuId);
    // 闸门二：该目的地的生效版本（没有就抛 409，绝不回退到基础）。
    EffectiveDestinationVersion destinationVersion = effective(skuId, code, at);
    CustomsProfile profile = customs.profile(skuId);

    CustomsDestinationVersionRules.Merged merged =
        CustomsDestinationVersionRules.merge(
            new CustomsDestinationVersionRules.MergeInput(
                base.hsCode(),
                base.declaredName(),
                base.statutoryUnitCode(),
                base.originCountryCode(),
                base.preferentialOriginEligible(),
                base.preferenceAgreement(),
                destinationVersion.destinationHsCode(),
                destinationVersion.destinationDeclaredName(),
                destinationVersion.preferentialApplicable()));

    return new ResolvedDeclaration(
        skuId,
        base.skuCode(),
        base.skuName(),
        code,
        destinationVersion.destinationVersionId().toString(),
        destinationVersion.versionNo(),
        destinationVersion.effectiveAt(),
        merged.declaredName(),
        merged.declaredNameSource(),
        merged.declaredNameOverridden(),
        merged.hsCode(),
        merged.hsCodeSource(),
        // 合成链路上基础属性必须已审核通过（闸门一），所以「这个码被人审过」恒成立。
        true,
        merged.hsCodeOverridden(),
        merged.originCountryCode(),
        merged.statutoryUnitCode(),
        // 申报要素**原封不动来自基础属性**（V95 第二节：本模块不复制它）。
        base.declarationElements(),
        CustomsDestinationVersionRules.SOURCE_BASE,
        CustomsDestinationVersionRules.ELEMENT_STATUS_COMPLETE,
        base.declarationElementsRef(),
        base.declaredValueReference(),
        merged.preferentialOriginEligible(),
        merged.preferentialApplicable(),
        merged.preferentialGranted(),
        merged.preferenceAgreement(),
        destinationVersion.requirements(),
        destinationVersion.publishedBy(),
        destinationVersion.publishedAt(),
        profile.reviewedBy(),
        profile.reviewedAt(),
        profile.note());
  }

  // ============================================================ 写入

  /**
   * 保存一个目的地版本（草稿态，一次提交写版本 + 认证要求全量清单）。
   *
   * <p><b>基础属性必须存在</b>（规则 B6）：外键能挡住，但它给出的报错在 RLS 下是 「违反外键约束」而不是「这个 SKU 还没维护基础报关属性」，运营看不懂也不知道下一步。
   * 所以这里先给一句人话，DB 的外键与触发器仍然是兜底。
   *
   * <p><b>已发布的版本不可直接改</b>（返回 409 而不是默默改掉）：改动一份 10 报关正在引用的申报资料，等于让它与发布结论不一致。正确路径是 「新建一个版本让新版本接替」（V25
   * 与 V94 的同一纪律）。
   */
  @Transactional
  public DestinationVersionSaved save(
      AccessService.Access actor, UUID skuId, DestinationVersionInput input) {
    try {
      Record sku = skuRow(skuId);
      UUID tenantId = sku.get("tenant_id", UUID.class);
      Normalized normalized = validate(tenantId, skuId, input);

      // 规则 B6：基础报关属性必须存在（服务层给可读错误，DB 外键是第二道）。
      Record attribute = attributeRow(tenantId, skuId);
      CatalogService.check(
          attribute != null,
          "这个 SKU 还没有基础报关属性（3.7.1.1），先在「HS 编码与申报要素」里维护并保存："
              + "目的地版本是在基础属性之上声明差异，没有基础属性就说不上「差异相对什么」");

      UUID versionId =
          writeVersion(actor, tenantId, skuId, attribute.get("id", UUID.class), input, normalized);
      writeRequirements(tenantId, versionId, normalized);

      audit(actor, skuId, "SkuCustomsDestinationVersionSaved");
      return new DestinationVersionSaved(loadVersion(tenantId, versionId));
    } catch (DataIntegrityViolationException e) {
      // B2–B5 的最终防线是库层；把它的中文原文透传出去（见 constraintViolation 的论证）。
      throw constraintViolation(e);
    }
  }

  /** 归一化后的入参（校验与归一在同一处完成，避免两处各写一遍规则）。 */
  private record Normalized(
      String destinationCountryCode,
      int versionNo,
      String destinationHsCode,
      String destinationDeclaredName,
      boolean preferentialApplicable,
      Instant effectiveFrom,
      Instant effectiveTo,
      String note,
      List<DestinationRequirementInput> requirements) {}

  /**
   * 校验与归一化。
   *
   * <p>应用层先拦一次是给<b>可读的中文错误</b>，DB 层的 CHECK 与触发器是兜底而不是唯一防线 （V95 第四节的纪律）：一条裸 SQL
   * 改不出可读的中文报错，而运营看不懂「23514」。
   */
  private Normalized validate(UUID tenantId, UUID skuId, DestinationVersionInput input) {
    String destination =
        rule(
            () ->
                CustomsDestinationVersionRules.normalizeDestination(
                    input.destinationCountryCode()));
    int versionNo =
        rule(() -> CustomsDestinationVersionRules.normalizeVersionNo(input.versionNo()));
    String hsCode =
        rule(
            () ->
                CustomsDestinationVersionRules.normalizeDestinationHsCode(
                    input.destinationHsCode()));
    String declaredName =
        rule(
            () ->
                CustomsDestinationVersionRules.normalizeDestinationDeclaredName(
                    input.destinationDeclaredName()));
    Instant from = input.effectiveFrom() == null ? null : input.effectiveFrom().toInstant();
    Instant to = input.effectiveTo() == null ? null : input.effectiveTo().toInstant();
    ruleVoid(() -> CustomsDestinationVersionRules.checkRangeOrdered(from, to));
    String note = input.note() == null ? "" : input.note().trim();
    CatalogService.check(note.length() <= 1000, "备注最多 1000 字");

    // 认证要求：清单上限 + 归一化 + 同一版本内认证码唯一。
    List<DestinationRequirementInput> requirements =
        input.requirements() == null ? List.of() : input.requirements();
    CatalogService.check(
        requirements.size() <= MAX_REQUIREMENTS, "一次最多保存 " + MAX_REQUIREMENTS + " 条认证要求");
    List<DestinationRequirementInput> normalized = new ArrayList<>(requirements.size());
    for (DestinationRequirementInput requirement : requirements) {
      String code =
          rule(
              () ->
                  CustomsDestinationVersionRules.normalizeRequirementCode(
                      requirement.requirementCode()));
      String name =
          requirement.requirementName() == null ? "" : requirement.requirementName().trim();
      String requirementNote = requirement.note() == null ? "" : requirement.note().trim();
      CatalogService.check(name.length() <= 200, "认证名称最多 200 字");
      CatalogService.check(requirementNote.length() <= 500, "认证备注最多 500 字");
      normalized.add(
          new DestinationRequirementInput(code, name, requirement.required(), requirementNote));
    }
    long distinctCodes =
        normalized.stream().map(DestinationRequirementInput::requirementCode).distinct().count();
    CatalogService.check(
        distinctCodes == normalized.size(),
        "同一个版本里认证代码不能重复：两行时「这个目的地要不要 CE」无法回答，" + "而 16 合规与清关仓会拿到两个互相矛盾的答案");

    return new Normalized(
        destination,
        versionNo,
        hsCode,
        declaredName,
        input.preferentialApplicable(),
        from,
        to,
        note,
        List.copyOf(normalized));
  }

  /**
   * 写版本行：先原地更新（带乐观锁），没有行时再插入 （与 {@code CustomsAttributeService.writeAttribute} 同一套形状）。
   *
   * <p><b>并发编辑两个人都提交时，后到的那个撞 409</b> 而不是静默覆盖先到的差异声明。 状态码必须是 <b>409 而不是 400</b>：400
   * 说的是「你这次请求写错了，请改输入」， 而这里请求本身完全合法、错的是<b>库里的状态已经变了</b>。
   *
   * <p><b>已发布版本唯一允许的写操作是「向前关闭生效区间」</b>（规则 R5 / B3）： 逐列比对一次，只放行 {@code effective_to} 收窄，其余任何一列变了都报错。
   * 这与 V25 的 {@code closeVersion} 是同一个动作，但走同一条保存路径 —— 分成独立端点会让运营在页面上多学一个概念，而「改这个版本」本来就是一个入口。
   */
  private UUID writeVersion(
      AccessService.Access actor,
      UUID tenantId,
      UUID skuId,
      UUID attributeId,
      DestinationVersionInput input,
      Normalized normalized) {
    Record existing =
        versionRow(tenantId, skuId, normalized.destinationCountryCode(), normalized.versionNo());
    if (existing != null) {
      UUID id = existing.get("id", UUID.class);
      boolean published =
          CustomsDestinationVersionRules.PUBLISHED.equals(existing.get("status", String.class));
      if (published) {
        // 已发布的版本不可原地改内容，只允许按规则 R5 向前关闭。
        Record current = fullVersionRow(tenantId, id);
        CatalogService.check(
            unchangedExceptInterval(current, normalized),
            "已发布的目的地报关版本不能直接改（税号 / 品名 / 享惠承认 / 生效起点 / 目的地 / 版本号 / 备注）："
                + "10 报关正在引用它，原地改掉等于让下游拿着与发布结论不一致的资料去申报。"
                + "要改请新建一个版本并让新版本接替；只有生效终点可以向前收窄");
        // 已发布版本的 UPDATE **只写 effective_to**：连 SET 列表里都不出现其它列，
        // 这样「只允许向前关闭」不依赖调用方记得先做一次比对。
        int closed =
            sql.execute(
                "update customs_destination_version set effective_to=?::timestamptz,"
                    + "version=version+1,updated_at=now()"
                    + " where tenant_id=? and id=? and version between ? and ?",
                CustomsDestinationVersionRules.toOffsetDateTime(normalized.effectiveTo()),
                tenantId,
                id,
                input.version() - 1,
                input.version());
        if (closed == 1) return id;
        throw new ResponseStatusException(HttpStatus.CONFLICT, "目的地报关版本已被他人修改，请刷新后重试");
      }
      int updated =
          sql.execute(
              "update customs_destination_version set customs_attribute_id=?,destination_hs_code=?,"
                  + "destination_declared_name=?,preferential_applicable=?,effective_from=?::timestamptz,"
                  + "effective_to=?::timestamptz,note=?,version=version+1,updated_at=now()"
                  + " where tenant_id=? and id=? and version between ? and ?",
              attributeId,
              normalized.destinationHsCode(),
              normalized.destinationDeclaredName(),
              normalized.preferentialApplicable(),
              CustomsDestinationVersionRules.toOffsetDateTime(normalized.effectiveFrom()),
              CustomsDestinationVersionRules.toOffsetDateTime(normalized.effectiveTo()),
              normalized.note(),
              tenantId,
              id,
              input.version() - 1,
              input.version());
      if (updated == 1) return id;
      throw new ResponseStatusException(HttpStatus.CONFLICT, "目的地报关版本已被他人修改，请刷新后重试");
    }
    UUID id = Ids.next();
    int inserted =
        sql.execute(
            "insert into customs_destination_version(id,tenant_id,sku_id,customs_attribute_id,"
                + "destination_country_code,version_no,destination_hs_code,destination_declared_name,"
                + "preferential_applicable,effective_from,effective_to,note)"
                + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?)"
                + " on conflict (tenant_id,sku_id,destination_country_code,version_no) do nothing",
            id,
            tenantId,
            skuId,
            attributeId,
            normalized.destinationCountryCode(),
            normalized.versionNo(),
            normalized.destinationHsCode(),
            normalized.destinationDeclaredName(),
            normalized.preferentialApplicable(),
            CustomsDestinationVersionRules.toOffsetDateTime(normalized.effectiveFrom()),
            CustomsDestinationVersionRules.toOffsetDateTime(normalized.effectiveTo()),
            normalized.note());
    if (inserted == 1) return id;
    throw new ResponseStatusException(HttpStatus.CONFLICT, "该目的地同版本号已被他人创建，请刷新后重试");
  }

  /**
   * 写认证要求（完整清单替换，不是增量）。
   *
   * <p>整体替换而不是增删式提交：「同一版本里认证码唯一」是跨行不变量， 前端增量提交时要自己算最终集合，漏对账会留下一组幽灵认证码 （与 3.7.1.1 申报要素、3.5.1.1
   * 组件清单同一取舍）。
   */
  private void writeRequirements(UUID tenantId, UUID versionId, Normalized normalized) {
    // 版本已发布时 DB 触发器 guard_destination_requirement_published 会拦，
    // 这里先行拦截（save 已拒改已发布版本，这里是第二道）。
    sql.execute(
        "delete from customs_destination_requirement where tenant_id=? and destination_version_id=?",
        tenantId,
        versionId);
    for (DestinationRequirementInput requirement : normalized.requirements())
      sql.execute(
          "insert into customs_destination_requirement(id,tenant_id,destination_version_id,"
              + "requirement_code,requirement_name,required,note) values(?,?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          versionId,
          requirement.requirementCode(),
          requirement.requirementName(),
          requirement.required(),
          requirement.note());
  }

  // ============================================================ 发布状态机

  /**
   * 发布一个目的地版本（草稿 → 已发布），需 {@code catalog.compliance.publish}。
   *
   * <p><b>发布是独立权限的理由</b>：能编辑目的地版本的人不一定有权把它发布成 10 报关清关时引用的那一份 —— 那等于把一份申报资料变成下游的事实。
   * 合并成一个权限等于允许「自己填自己发」，而那正是蓝图点名要防的 「用一套申报资料打通全球」的另一个入口。
   *
   * <p><b>区间重叠（规则 R2）在发布这一刻才检查</b>（规则 R3）：草稿之间随便重叠， 由 DB 触发器 {@code
   * guard_destination_version_interval} 拒绝，报错点名与哪一版撞了。
   *
   * <p><b>发布是单向的</b>：撤回发布不是合法动作（DB 触发器钉死）， 正确路径是新建一个版本让新版本接替。
   */
  @Transactional
  public DestinationVersionRow publish(
      AccessService.Access actor,
      UUID versionId,
      DestinationPublishInput input,
      boolean publishPermission) {
    try {
      // **权限判定先于取数**：发布是一个独立权限，而取数要过 RLS。
      // 顺序反了的话，一个只有 read+update 的编辑者拿到的会是
      // 「记录不存在或没有访问权限」404 —— 把「你没这个权限」说成「这行不存在」，
      // 运营会以为是自己查错了 SKU，而真正要改的是授权。
      CatalogService.check(publishPermission, "没有发布权限：能编辑目的地报关版本不等于有权把它发布成 10 报关清关时引用的那一份");
      Record row = versionRow(versionId);
      CatalogService.check(row != null, "这个目的地报关版本不存在，或者当前租户看不到它");
      UUID tenantId = row.get("tenant_id", UUID.class);
      CatalogService.check(
          CustomsDestinationVersionRules.DRAFT.equals(row.get("status", String.class)),
          "只有草稿可以发布：已发布的版本不能撤回发布，也不能重复发布");

      int updated =
          sql.execute(
              "update customs_destination_version set status='PUBLISHED',published_by=?,published_at=now(),"
                  + "version=version+1,updated_at=now()"
                  + " where tenant_id=? and id=? and version between ? and ? and status='DRAFT'",
              actor.userId(),
              tenantId,
              versionId,
              input.version() - 1,
              input.version());
      if (updated != 1)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "目的地报关版本已被他人修改，请刷新后重试");

      audit(actor, row.get("sku_id", UUID.class), "SkuCustomsDestinationVersionPublished");
      return loadVersion(tenantId, versionId);
    } catch (DataIntegrityViolationException e) {
      // 区间重叠（B1）正是在这一刻才检查的，而它的判定在触发器里：
      // 不翻译的话这条最重要的业务规则会以 500 的形态出现。详见 constraintViolation。
      throw constraintViolation(e);
    }
  }

  // ============================================================ 取数辅助

  /** SKU 行（含租户）；不存在或当前租户看不到它都返回 {@code null}。 */
  private Record skuRow(UUID skuId) {
    Record row =
        sql.fetchOne(
            "select s.tenant_id,s.id,s.code,s.name,p.code as spu_code"
                + " from sku s"
                + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where s.id=?",
            skuId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private UUID skuTenantId(UUID skuId) {
    return skuRow(skuId).get("tenant_id", UUID.class);
  }

  /** 3.7.1.1 的基础报关属性行；{@code null} = 尚未维护（真实状态，不是错误）。 */
  private Record attributeRow(UUID tenantId, UUID skuId) {
    return sql.fetchOne(
        "select id,status from customs_attribute where tenant_id=? and sku_id=?", tenantId, skuId);
  }

  /** 某 (SKU, 目的地, 版本号) 的版本行。 */
  private Record versionRow(UUID tenantId, UUID skuId, String destination, int versionNo) {
    return sql.fetchOne(
        "select id,tenant_id,sku_id,status,version from customs_destination_version"
            + " where tenant_id=? and sku_id=? and destination_country_code=? and version_no=?",
        tenantId,
        skuId,
        destination,
        versionNo);
  }

  /** 按 id 取版本行；{@code null} = 不存在或当前租户看不到它。 */
  private Record versionRow(UUID versionId) {
    return sql.fetchOne(
        "select id,tenant_id,sku_id,destination_country_code,version_no,status,version"
            + " from customs_destination_version where id=?",
        versionId);
  }

  /** 按 id 取带覆盖字段与区间的完整版本行（判断「除终点外都没变」时用）。 */
  private Record fullVersionRow(UUID tenantId, UUID versionId) {
    return sql.fetchOne(
        "select id,sku_id,customs_attribute_id,destination_country_code,version_no,"
            + "destination_hs_code,destination_declared_name,preferential_applicable,"
            + "effective_from,effective_to,note"
            + " from customs_destination_version where tenant_id=? and id=?",
        tenantId,
        versionId);
  }

  /**
   * 已发布版本的「只有终点变了」判定（规则 R5）。
   *
   * <p>逐列比对入参能携带的那些列，{@code effective_to} 是唯一被允许不同的列， 而且必须<b>收窄</b>：改成 {@code
   * null}（重开区间）或往后延都会与接替它的版本重叠。 DB 的 {@code guard_destination_version_published} 是这里的兜底，
   * 这一层负责先给一句人话而不是让运营看到 RAISE 出来的中文原文。
   *
   * <p><b>为什么这里不比对 {@code sku_id} 与 {@code customs_attribute_id}</b>： 它们根本不在 {@link
   * DestinationVersionInput} 里，调用方没有途径改它们， 比对两个不可能不同的值只会让读者以为这里还有一道校验。 真正的防线是那条<b>只写 effective_to 的
   * UPDATE</b>（见 {@link #writeVersion}）。
   */
  private boolean unchangedExceptInterval(Record current, Normalized normalized) {
    return current.get("destination_hs_code", String.class).equals(normalized.destinationHsCode())
        && current
            .get("destination_declared_name", String.class)
            .equals(normalized.destinationDeclaredName())
        && Boolean.TRUE.equals(current.get("preferential_applicable", Boolean.class))
            == normalized.preferentialApplicable()
        && current
            .get("effective_from", OffsetDateTime.class)
            .toInstant()
            .equals(normalized.effectiveFrom())
        && blankToEmpty(current.get("note", String.class)).equals(normalized.note())
        && narrowsForward(
            current.get("effective_to", OffsetDateTime.class), normalized.effectiveTo());
  }

  /** 终点只能收窄：{@code null → 非 null} 或「较晚 → 较早」；不允许重开或往后延。 */
  private boolean narrowsForward(OffsetDateTime currentTo, Instant nextTo) {
    if (currentTo == null) return nextTo != null;
    if (nextTo == null) return false;
    return nextTo.isBefore(currentTo.toInstant());
  }

  /**
   * 规则 R4 的 SQL：{@code from <= t AND (to IS NULL OR t < to)}， 半开区间由 {@code <} 而不是 {@code <=}
   * 保证（{[10:00,12:00)} 与 [12:00,14:00) 不重叠）。
   */
  private Record effectiveRow(UUID tenantId, UUID skuId, String destination, Instant at) {
    return sql.fetchOne(
        "select id,tenant_id,sku_id,destination_country_code,version_no,destination_hs_code,"
            + "destination_declared_name,preferential_applicable,status,published_by,published_at,"
            + "effective_from,effective_to,version,updated_at"
            + " from customs_destination_version"
            + " where tenant_id=? and sku_id=? and destination_country_code=?"
            + " and status='PUBLISHED'"
            + " and effective_from<=?::timestamptz"
            + " and (effective_to is null or ?::timestamptz<effective_to)"
            + " order by effective_from desc,id limit 1",
        tenantId,
        skuId,
        destination,
        CustomsDestinationVersionRules.toOffsetDateTime(at),
        CustomsDestinationVersionRules.toOffsetDateTime(at));
  }

  /** 一次取回多个版本的认证要求（避免逐版本查的 N+1）。 */
  private Map<UUID, List<DestinationRequirement>> requirementsByVersion(
      UUID tenantId, List<UUID> versionIds) {
    Map<UUID, List<DestinationRequirement>> grouped = new HashMap<>();
    if (versionIds.isEmpty()) return grouped;
    // 占位符按行数拼而不是走 jOOQ 的 DSL：服务层其它 SQL 同样是手写语句 + ? 绑定，
    // 这里保持同一形状，避免同一个类里两种取数风格。
    String placeholders = versionIds.stream().map(id -> "?").collect(Collectors.joining(","));
    List<Object> binds = new ArrayList<>(versionIds.size() + 1);
    binds.add(tenantId);
    binds.addAll(versionIds);
    List<Record> rows =
        sql.fetch(
            "select id,destination_version_id,requirement_code,requirement_name,required,note"
                + " from customs_destination_requirement"
                + " where tenant_id=? and destination_version_id in ("
                + placeholders
                + ") order by destination_version_id,requirement_code",
            binds.toArray());
    for (Record r : rows) {
      String note = r.get("note", String.class);
      grouped
          .computeIfAbsent(r.get("destination_version_id", UUID.class), key -> new ArrayList<>())
          .add(
              new DestinationRequirement(
                  r.get("id", UUID.class),
                  r.get("requirement_code", String.class),
                  r.get("requirement_name", String.class),
                  Boolean.TRUE.equals(r.get("required", Boolean.class)),
                  note == null ? "" : note));
    }
    return grouped;
  }

  /** 单版本详情（发布后回读 / 保存后回读）。 */
  private DestinationVersionRow loadVersion(UUID tenantId, UUID versionId) {
    Record row =
        sql.fetchOne(
            "select id,sku_id,version_no,destination_country_code,destination_hs_code,"
                + "destination_declared_name,preferential_applicable,status,published_by,published_at,"
                + "effective_from,effective_to,note,version,updated_at"
                + " from customs_destination_version where tenant_id=? and id=?",
            tenantId,
            versionId);
    CatalogService.check(row != null, "这个目的地报关版本不存在，或者当前租户看不到它");
    Record effective =
        effectiveRow(
            tenantId,
            row.get("sku_id", UUID.class),
            row.get("destination_country_code", String.class),
            Instant.now());
    UUID effectiveId = effective == null ? null : effective.get("id", UUID.class);
    return toVersionRow(
        row, effectiveId, requirementsByVersion(tenantId, List.of(versionId)).get(versionId));
  }

  private DestinationVersionRow toVersionRow(
      Record row, UUID effectiveId, List<DestinationRequirement> requirements) {
    UUID id = row.get("id", UUID.class);
    boolean effective = id.equals(effectiveId);
    String status = row.get("status", String.class);
    Instant from = row.get("effective_from", OffsetDateTime.class).toInstant();
    String to = instant(row.get("effective_to", OffsetDateTime.class));
    return new DestinationVersionRow(
        id,
        row.get("version_no", Integer.class),
        row.get("destination_country_code", String.class),
        blankToEmpty(row.get("destination_hs_code", String.class)),
        blankToEmpty(row.get("destination_declared_name", String.class)),
        Boolean.TRUE.equals(row.get("preferential_applicable", Boolean.class)),
        status,
        row.get("published_by", UUID.class),
        instant(row.get("published_at", OffsetDateTime.class)),
        from.toString(),
        to,
        effective,
        blankToEmpty(row.get("note", String.class)),
        row.get("version", Long.class),
        requirements == null ? List.of() : requirements,
        instant(row.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 「该目的地无生效版本」的报错。
   *
   * <p><b>刻意不写「将使用基础报关属性」这类降级说明</b>：一旦文案里出现降级， 调用方就会把它当成一个正常分支来做兜底，而兜底的结果正是蓝图要消灭的
   * 「用一套申报资料打通全球」。这句话必须同时说清<b>后果</b>与<b>下一步</b>。
   */
  private ResponseStatusException noEffectiveVersion(String destination, UUID skuId, Instant at) {
    return new ResponseStatusException(
        HttpStatus.CONFLICT,
        "SKU "
            + skuId
            + " 在目的地 "
            + destination
            + " 于 "
            + at
            + " 没有生效的报关版本：不同目的地必须用不同版本，不能用一套申报资料打通全球，"
            + "所以这里不提供「回退到基础报关属性」的降级（那正是这个模块要防的事）。"
            + "请先为该目的地发布一个覆盖当前时刻的版本");
  }

  /** 归一化查询时刻：不传 {@code at} 就是查此刻（与 BOM 的 effective 端点同一口径）。 */
  private static Instant momentOf(String at) {
    Instant parsed = rule(() -> CustomsDestinationVersionRules.parseInstant(at, "查询时刻"));
    // 不传就查「此刻」：让最常用的调用方式变成 500 是不可接受的。
    return parsed == null ? Instant.now() : parsed;
  }

  /** {@code timestamptz → ISO-8601 字符串}（响应层用字符串，时区不丢）。 */
  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  // ============================================================ 公共

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  /**
   * 把 Rules 的失败契约翻译成服务层的失败契约：<b>类型翻译，不是吞异常</b>。
   *
   * <p>{@link CustomsDestinationVersionRules} 是纯函数、不依赖 Web 层，所以它用 {@link IllegalArgumentException}
   * 表达「入参非法」；但本服务的所有失败出口都是 {@link ResponseStatusException}。少了这一步翻译，一次目的国填错会以 {@code
   * IllegalArgumentException} 的形态漏到 {@code ApiErrorHandler}， 运营看到的是「字段格式不正确」而不是「不能用 '*' 打通全球」。
   *
   * <p><b>与 {@link CustomsAttributeService} 的同名私有方法分开</b>： 那个类里已经有 {@code
   * rule(Supplier<T>)}，本类不共用以免两处各有一份翻译逻辑 以后只改一处。
   */
  private static <T> T rule(Supplier<T> rule) {
    try {
      return rule.get();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  /** 无返回值的规则调用（与 {@link #rule} 同一套翻译，刻意不同名以避开 lambda 重载歧义）。 */
  private static void ruleVoid(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  /**
   * 把库层约束违规翻译成服务层的失败契约。
   *
   * <p><b>为什么必须有这一步</b>：B1–B5 的最终防线是触发器与 CHECK，它们抛出来的是 {@code DataIntegrityViolationException}，经
   * ApiErrorHandler 会变成 <b>500</b> —— 也就是说「区间重叠」「目的国通配」「已发布不可撤回」这些<b>业务规则</b>， 在 HTTP
   * 层会以「服务器内部错误」的形式出现，而它们明明是调用方可以改正的输入问题。 触发器里的中文原文是为<b>人</b>写的，必须把它透传到响应里，否则就白写了。
   *
   * <p><b>409 而不是 400</b>：请求本身合法（JSON 全部合法、字段全部有值）， 错的是<b>库里的状态</b>——已经有一个生效版本占了那段时间。 与本类乐观锁撞 409
   * 是同一条口径。
   */
  private static ResponseStatusException constraintViolation(DataIntegrityViolationException e) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    int at = message.indexOf("ERROR:  ");
    String detail = at >= 0 ? message.substring(at + "ERROR:  ".length()) : message;
    // PG 会把「在位置：PL/pgSQL function ...」另起一行附在后面，那段对调用方没有意义。
    int newline = detail.indexOf('\n');
    if (newline >= 0) detail = detail.substring(0, newline);
    detail = detail.trim();
    return new ResponseStatusException(
        HttpStatus.CONFLICT, detail.isEmpty() ? "操作违反了目的地报关版本的数据约束，请刷新后重试" : detail, e);
  }

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }
}
