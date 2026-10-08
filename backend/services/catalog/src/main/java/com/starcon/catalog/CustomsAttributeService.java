package com.starcon.catalog;

import static com.starcon.catalog.CustomsAttributeModels.*;
import static com.starcon.catalog.CustomsAttributeRules.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.7.1.1 HS 编码与申报要素：报关属性的读写、申报要素维护、审核状态机与
 * <b>供 10 报关引用的只读出口</b>。
 *
 * <p>蓝图原文：「职责：维护商品的 HS 编码、原产地、申报品名、申报价值参考、法定计量单位、
 * 是否享惠（RCEP 等），供 10 报关清关生成申报资料。」
 * 「输出：报关属性记录；供申报单引用。」
 * 「下游 = 10 物流与头程（报关）、16 合规（税则校验）。
 * <b>HS 编码错误直接导致申报失败与罚款</b>，必须允许审核流程而非任意填写。」
 *
 * <p><b>本模块存在的理由就是最后那句</b>：{@link #declarationReady} 对未审核通过的行
 * <b>一律报错</b>，而不是返回一份「看起来能用的数据」。草稿对下游不可见，
 * 这与 3.7.2.1 的 {@code SAFETY_NOT_COLLECTED} 拒发是同一条纪律。
 *
 * <p><b>不落派生值</b>（V94 零之三）：可报关性现算；完税价格 / 申报货值是
 * 10 报关与 12 财务的事实，本模块只给 {@code declared_value_reference} 作参考值。
 *
 * <p><b>权限口径</b>：读 {@code catalog.compliance.read}、写 {@code catalog.compliance.update}、
 * <b>审核 {@code catalog.compliance.review}</b>。
 * 审核单独一个权限的理由：<b>能编辑报关属性的人不一定有权批准它</b> ——
 * 填 HS 码是日常录入，批准一个将变成真实报关单与税额的申报事实是另一回事。
 * 合并成一个权限等于允许「自己填自己批」，而那正是蓝图点名要防的「任意填写」。
 */
@Service
public class CustomsAttributeService {
  /** 单页最大行数（筛选列表页）。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 一次保存的申报要素条数上限：报关要素就十几项，上限只是防手滑。 */
  static final int MAX_ELEMENTS = 100;

  private final DSLContext sql;

  public CustomsAttributeService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 读取

  /** 读单个 SKU 的报关属性 + 申报要素 + 现算的可报关性判定。 */
  public CustomsProfile profile(UUID skuId) {
    return load(skuId);
  }

  /**
   * 一条 SQL 取出行；没有就 404。
   *
   * <p>「SKU 存在但没维护报关属性」**不是** 404 的理由 —— 那是列表页的常态空态，
   * 所以读属性用 {@code left join} 取 null 行，而只读报关资料时
   * {@link #declarationReady} 才把 null 判成「不可报关」（那条路径才该报错）。
   */
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

  /** 报关属性行；{@code null} = 尚未维护（不是错误，是一种真实状态）。 */
  private Record attributeRow(UUID skuId) {
    return sql.fetchOne(
        "select id,hs_code,origin_country_code,declared_name,declared_value_reference,"
            + "statutory_unit_code,preferential_origin_eligible,preference_agreement,status,"
            + "submitted_by,submitted_at,reviewed_by,reviewed_at,reject_reason,note,version,updated_at"
            + " from customs_attribute where sku_id=?",
        skuId);
  }

  /** 申报要素行，按位次升序（纪律 4：顺序是业务事实，不能依赖 SQL 返回顺序）。 */
  private List<Record> elementRows(UUID attributeId) {
    return sql.fetch(
        "select id,ordinal,element_code,element_name,element_value,required"
            + " from customs_declaration_element where customs_attribute_id=? order by ordinal",
        attributeId);
  }

  private List<DeclarationElement> elements(UUID attributeId) {
    if (attributeId == null) return List.of();
    return ordered(elementRows(attributeId).stream().map(this::toElement).toList());
  }

  private DeclarationElement toElement(Record row) {
    String value = row.get("element_value", String.class);
    boolean required = Boolean.TRUE.equals(row.get("required", Boolean.class));
    return new DeclarationElement(
        row.get("id", UUID.class),
        row.get("ordinal", Integer.class),
        row.get("element_code", String.class),
        row.get("element_name", String.class),
        value,
        required,
        required && (value == null || value.trim().isEmpty()));
  }

  /**
   * 组装档案：SKU 身份 + 属性 + 申报要素 + <b>现算的可报关性</b>。
   *
   * <p>{@code attribute == null}（尚未维护）是一个<b>真实状态</b>而不是错误：
   * 列表页要能显示「未维护」，所以返回一份字段为空、判定为 NOT_RECORDED 的档案。
   * 只有 {@link #declarationReady} 才把它变成一个错误 —— 那条路径是 10 报关的取数口，
   * 拿不到就该报错而不是拿到一份空资料。
   *
   * <p>{@code version} / {@code submittedAt} 必须如实回传：
   * 前端拿 version 做 CAS，恒回 0 会让第二次保存永远撞 409；
   * {@code submittedAt} 是「草稿」与「已提交待审」的唯一区别（status 里没有 PENDING）。
   */
  private CustomsProfile assemble(Record sku, Record attribute) {
    UUID skuId = sku.get("id", UUID.class);
    String skuCode = sku.get("code", String.class);
    String skuName = sku.get("name", String.class);
    String spuCode = sku.get("spu_code", String.class);

    if (attribute == null) {
      CustomsProfile blank =
          new CustomsProfile(
              skuId,
              skuCode,
              skuName,
              spuCode,
              null, // attributeId：尚未维护
              null, // hsCode
              null, // originCountryCode
              null, // declaredName
              null, // declaredValueReference
              null, // statutoryUnitCode
              null, // preferentialOriginEligible
              null, // preferenceAgreement
              null, // status
              null, // submittedBy
              null, // submittedAt
              null, // reviewedBy
              null, // reviewedAt
              null, // rejectReason
              null, // note
              null, // version
              List.of(),
              false,
              List.of(),
              List.of(),
              null);
      return withVerdict(blank);
    }

    List<DeclarationElement> orderedElements = ordered(elements(attribute.get("id", UUID.class)));
    CustomsProfile draft =
        new CustomsProfile(
            skuId,
            skuCode,
            skuName,
            spuCode,
            attribute.get("id", UUID.class),
            attribute.get("hs_code", String.class),
            attribute.get("origin_country_code", String.class),
            attribute.get("declared_name", String.class),
            attribute.get("declared_value_reference", BigDecimal.class),
            attribute.get("statutory_unit_code", String.class),
            attribute.get("preferential_origin_eligible", Boolean.class),
            attribute.get("preference_agreement", String.class),
            attribute.get("status", String.class),
            attribute.get("submitted_by", UUID.class),
            instant(attribute.get("submitted_at", OffsetDateTime.class)),
            attribute.get("reviewed_by", UUID.class),
            instant(attribute.get("reviewed_at", OffsetDateTime.class)),
            attribute.get("reject_reason", String.class),
            attribute.get("note", String.class),
            attribute.get("version", Long.class),
            orderedElements,
            false,
            List.of(),
            List.of(),
            instant(attribute.get("updated_at", OffsetDateTime.class)));
    // 派生判定统一从 Rules 走，保证档案与 /declaration-ready 用的是同一套。
    return withVerdict(draft);
  }

  /** 把 {@link CustomsAttributeRules#verdict} 的判定结果回填进档案（两个构造出口共用一份逻辑）。 */
  private static CustomsProfile withVerdict(CustomsProfile profile) {
    var result = CustomsAttributeRules.verdict(profile, profile.elements());
    return new CustomsProfile(
        profile.skuId(),
        profile.skuCode(),
        profile.skuName(),
        profile.spuCode(),
        profile.attributeId(),
        profile.hsCode(),
        profile.originCountryCode(),
        profile.declaredName(),
        profile.declaredValueReference(),
        profile.statutoryUnitCode(),
        profile.preferentialOriginEligible(),
        profile.preferenceAgreement(),
        profile.status(),
        profile.submittedBy(),
        profile.submittedAt(),
        profile.reviewedBy(),
        profile.reviewedAt(),
        profile.rejectReason(),
        profile.note(),
        profile.version(),
        profile.elements(),
        result.ready(),
        result.blockers(),
        result.blockers().stream().map(CustomsAttributeRules::blockerLabel).toList(),
        profile.updatedAt());
  }

  /** 读档案（含 SKU 身份）。属性表本身不冗余存 SKU 编码，所以要 join 一次 sku。 */
  private CustomsProfile load(UUID skuId) {
    Record sku = skuRow(skuId);
    return assemble(sku, attributeRow(skuId));
  }

  /**
   * {@code timestamptz → ISO-8601 字符串}。
   *
   * <p>响应层一律用字符串而不是让 Jackson 序列化成时间戳数组：
   * 后者在前端与审计日志里都不可读，而且时区丢失后无法回答「当时按哪个时区报的」。
   */
  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  // ============================================================ 写入

  /**
   * 保存报关属性与申报要素（一次提交写两张表，{@code elements} 是完整清单）。
   *
   * <p><b>已审核通过的属性不可直接改内容</b>：改动一份 10 报关正在引用的申报事实，
   * 等于让它与审核结论不一致。正确路径是「审核里驳回 → 改 → 重新提交」。
   * 这里返回 409 而不是默默改掉。
   */
  @Transactional
  public CustomsSaved save(AccessService.Access actor, UUID skuId, CustomsInput input) {
    Record sku = skuRow(skuId);
    UUID tenantId = sku.get("tenant_id", UUID.class);
    Normalized normalized = validate(tenantId, skuId, input);

    // 先写属性再删改要素：属性行必须先存在，FK 才接得住要素行。
    UUID attributeId = writeAttribute(actor, tenantId, skuId, input, normalized);
    writeElements(actor, tenantId, attributeId, normalized);

    audit(actor, skuId, "SkuCustomsAttributeSaved");
    return new CustomsSaved(load(skuId));
  }

  /** 归一化后的入参（校验与归一在同一处完成，避免两处各写一遍规则）。 */
  private record Normalized(
      String hsCode,
      String originCountryCode,
      String declaredName,
      BigDecimal declaredValueReference,
      String statutoryUnitCode,
      String preferenceAgreement,
      String note,
      List<DeclarationElementInput> elements) {}

  /**
   * 把 Rules 的失败契约翻译成服务层的失败契约：<b>类型翻译，不是吞异常</b>。
   *
   * <p>{@link CustomsAttributeRules} 是纯函数、不依赖 Web 层，所以它用
   * {@link IllegalArgumentException} 表达「入参非法」；但本服务的所有失败出口都是
   * {@link ResponseStatusException}（{@code CatalogService.check} 也是这么写的），
   * 状态码与文案都挂在异常上。少了这一步翻译，一次 HS 编码填错会以
   * {@code IllegalArgumentException} 的形态漏到 {@code ApiErrorHandler}，
   * 运营看到的是「字段格式不正确」而不是「必须 6–10 位纯数字」——
   * 而后者才是他要的下一步动作。
   *
   * <p>文案原样透传、异常照传（{@code cause} 不丢），所以单元测试仍可在 Rules 上
   * 逐条断言 {@link IllegalArgumentException}，服务层则拿到带 400 的异常。
   */
  private static <T> T rule(Supplier<T> rule) {
    try {
      return rule.get();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  /**
   * 校验与归一化。
   *
   * <p>应用层先拦一次是给<b>可读的中文错误</b>，DB 层的 CHECK 与触发器是兜底而不是唯一防线
   * （V94 第三节的纪律）：一条裸 SQL 改不出可读的中文报错，而运营看不懂「23514」。
   */
  private Normalized validate(UUID tenantId, UUID skuId, CustomsInput input) {
    String hsCode = rule(() -> normalizeHsCode(input.hsCode()));
    String origin = rule(() -> normalizeCountryCode(input.originCountryCode(), "原产地"));
    String agreement =
        rule(() -> normalizePreference(input.preferentialOriginEligible(), input.preferenceAgreement()));
    String declaredName = input.declaredName().trim();
    String statutoryUnit = input.statutoryUnitCode().trim();
    String note = input.note() == null ? "" : input.note().trim();

    CatalogService.check(!declaredName.isEmpty(), "请填写申报品名");
    CatalogService.check(
        declaredName.length() <= 200,
        "申报品名最多 200 字：它会原样出现在报关单上，长度不够会截断成另一个商品");
    CatalogService.check(!statutoryUnit.isEmpty(), "请填写法定计量单位代码");
    CatalogService.check(
        statutoryUnit.length() <= 8, "法定计量单位代码最多 8 个字符（例：PCE 件 / KGM 千克）");
    CatalogService.check(note.length() <= 1000, "备注最多 1000 字");
    BigDecimal value = input.declaredValueReference();
    if (value != null) {
      CatalogService.check(value.signum() > 0, "申报价值参考必须大于 0");
      CatalogService.check(
          value.scale() <= 6,
          "申报价值参考最多 6 位小数：多出的位数会被数据库静默四舍五入，"
              + "而你报的那个数就不存在了");
    }

    // 申报要素：位次必须从 1 起且不重复（DB 也有约束，这里先给可读错误）。
    List<DeclarationElementInput> elements =
        input.elements() == null ? List.of() : input.elements();
    CatalogService.check(
        elements.size() <= MAX_ELEMENTS, "一次最多保存 " + MAX_ELEMENTS + " 条申报要素");
    List<DeclarationElementInput> sorted =
        elements.stream().sorted((left, right) -> Integer.compare(left.ordinal(), right.ordinal())).toList();
    for (DeclarationElementInput element : sorted) {
      CatalogService.check(element.ordinal() > 0, "申报要素位次必须从 1 开始");
      CatalogService.check(
          element.elementCode() != null && !element.elementCode().trim().isEmpty(),
          "申报要素代码不能为空（例：01 品名 / 02 品牌类型）");
      CatalogService.check(
          element.elementCode().trim().length() <= 32, "申报要素代码最多 32 个字符");
      CatalogService.check(
          element.elementName() != null && !element.elementName().trim().isEmpty(),
          "申报要素名称不能为空");
      CatalogService.check(element.elementName().trim().length() <= 200, "申报要素名称最多 200 字");
    }
    long distinctOrdinals = sorted.stream().map(DeclarationElementInput::ordinal).distinct().count();
    CatalogService.check(
        distinctOrdinals == sorted.size(),
        "申报要素位次不能重复：同一属性下同一位次不能有两行，否则「这一位填什么」无法回答");

    // 未维护（首次保存）或已有行都要走一次 element 行级校验：归一化元素值。
    return new Normalized(
        hsCode, origin, declaredName, value, statutoryUnit, agreement, note, sorted);
  }

  /**
   * 写属性行：先原地更新（带乐观锁），没有行时再插入（与
   * {@code PhysicalSafetyService.writeSafety} 同一套形状）。
   *
   * <p><b>并发编辑两个人都提交时，后到的那个撞 409</b> 而不是静默覆盖先到的申报事实。
   * 状态码必须是 <b>409 而不是 400</b>：400 说的是「你这次请求写错了，请改输入」，
   * 而这里请求本身完全合法、错的是**库里的状态已经变了**（全服务统一口径见
   * {@code PhysicalSafetyService} 的包装尺寸/安全属性两处 CONFLICT）。
   *
   * <p><b>改内容会把这行打回 DRAFT 并清掉上一轮的提交与审核痕迹</b>：
   * 驳回后改内容改的是另一份申报事实，「上一轮谁交上来的、谁驳的」已经与新内容无关，
   * 留着会让审核队列里混进一条内容从没被提交过、却带着旧留痕的行
   * （V94 的 {@code customs_attribute_draft_unreviewed} 也从库层钉住了 DRAFT 不得带审核痕迹）。
   * 提交痕迹同样要清：{@code customs_attribute_review_queue} 正是按
   * {@code status='DRAFT' AND submitted_at IS NOT NULL} 建的，不清会让改完还没重提的行提前进队列。
   */
  private UUID writeAttribute(
      AccessService.Access actor, UUID tenantId, UUID skuId, CustomsInput input, Normalized normalized) {
    // 已有行且已审核通过时拒改（草稿态编辑是本端点的唯一场景）。
    Record existing = attributeRow(skuId);
    if (existing != null)
      CatalogService.check(
          !"APPROVED".equals(existing.get("status", String.class)),
          "已审核通过的报关属性不能直接改：请先在审核里驳回它再改");

    int updated =
        sql.execute(
            "update customs_attribute set hs_code=?,origin_country_code=?,declared_name=?,"
                + "declared_value_reference=?,statutory_unit_code=?,preferential_origin_eligible=?,"
                + "preference_agreement=?,note=?,status='DRAFT',submitted_by=null,submitted_at=null,"
                + "reviewed_by=null,reviewed_at=null,reject_reason='',version=version+1,updated_at=now()"
                + " where tenant_id=? and sku_id=? and version between ? and ?",
            normalized.hsCode(), normalized.originCountryCode(), normalized.declaredName(),
            normalized.declaredValueReference(), normalized.statutoryUnitCode(),
            input.preferentialOriginEligible(), normalized.preferenceAgreement(), normalized.note(),
            tenantId, skuId, input.version() - 1, input.version());
    if (updated == 1) return attributeId(tenantId, skuId);
    // 没有这一行时 version 记为 0，插入路径不带 CAS（并发插入由唯一约束挡住）。
    int inserted =
        sql.execute(
            "insert into customs_attribute(id,tenant_id,sku_id,hs_code,origin_country_code,"
                + "declared_name,declared_value_reference,statutory_unit_code,"
                + "preferential_origin_eligible,preference_agreement,note)"
                + " values(?,?,?,?,?,?,?,?,?,?,?)"
                + " on conflict (tenant_id,sku_id) do nothing",
            Ids.next(), tenantId, skuId, normalized.hsCode(), normalized.originCountryCode(),
            normalized.declaredName(), normalized.declaredValueReference(), normalized.statutoryUnitCode(),
            input.preferentialOriginEligible(), normalized.preferenceAgreement(), normalized.note());
    if (inserted == 1) return attributeId(tenantId, skuId);
    throw new ResponseStatusException(HttpStatus.CONFLICT, "报关属性已被他人修改，请刷新后重试");
  }

  private UUID attributeId(UUID tenantId, UUID skuId) {
    return sql.fetchOne(
            "select id from customs_attribute where tenant_id=? and sku_id=?", tenantId, skuId)
        .get("id", UUID.class);
  }

  /**
   * 写申报要素（完整清单替换，不是增量）。
   *
   * <p>整体替换而不是增删式提交：位次唯一与「required 非空」都是跨行不变量，
   * 前端增量提交时要自己算最终集合，漏对账会留下一组幽灵位次
   * （与 3.5.1.1 {@code PUT /versions/{id}/components} 同一取舍）。
   */
  private void writeElements(
      AccessService.Access actor, UUID tenantId, UUID attributeId, Normalized normalized) {
    // 属性已审核通过时 DB 触发器 guard_customs_element_under_review 会拦删改，
    // 这里先行拦截并给可读错误（save 已拒改，这里是第二道）。
    sql.execute(
        "delete from customs_declaration_element where tenant_id=? and customs_attribute_id=?",
        tenantId, attributeId);
    for (DeclarationElementInput element : normalized.elements()) {
      String value = element.elementValue() == null ? "" : element.elementValue().trim();
      sql.execute(
          "insert into customs_declaration_element(id,tenant_id,customs_attribute_id,ordinal,"
              + "element_code,element_name,element_value,required) values(?,?,?,?,?,?,?,?)",
          Ids.next(), tenantId, attributeId, element.ordinal(),
          element.elementCode().trim(), element.elementName().trim(), value, element.required());
    }
  }

  // ============================================================ 审核状态机

  /**
   * 提交审核（DRAFT → 已提交待审）。
   *
   * <p><b>status 词表里没有 PENDING</b>（只有 DRAFT / APPROVED / REJECTED），
   * 所以「已提交待审」由 {@code submitted_by / submitted_at} 承担。
   * 若不记这两列，「已提交无人审」与「还没提交」是同一行，审核队列不存在，
   * 而本端点会退化成一次没有效果的点击。
   *
   * <p>只允许 DRAFT 提交：已审核通过的改内容要走「驳回 → 改 → 重新提交」，
   * 被驳回的先改内容（改内容会把状态清回 DRAFT，见 writeAttribute）。
   *
   * <p><b>乐观锁同样是 409 而不是 400</b>（理由见 {@link #writeAttribute}）：
   * 请求本身合法，错的是这一行在提交前被别人改过。
   *
   * <p><b>version 的匹配窗口是「库里当前版本」与「它 +1」两格</b>，
   * 与 {@link #writeAttribute} 同一口径：前端 CAS 有两种合法写法 ——
   * 传它读到的版本，或传它把本次写入也算进去的下一个版本。
   * 落后 1 步以上一律 409，那才是真正的并发覆盖（有人改了申报事实而你还拿着旧版本）。
   * 窗口只开 +1 而不是无上限，是刻意的：开大了就等于允许重放旧数据覆盖申报资料。
   */
  @Transactional
  public CustomsProfile submit(AccessService.Access actor, UUID skuId, SubmitInput input) {
    Record sku = skuRow(skuId);
    UUID tenantId = sku.get("tenant_id", UUID.class);
    Record attribute = attributeRow(skuId);
    CatalogService.check(attribute != null, "这个 SKU 还没有报关属性，先保存再提交审核");

    int updated =
        sql.execute(
            "update customs_attribute set submitted_by=?,submitted_at=now(),"
                + "reviewed_by=null,reviewed_at=null,reject_reason='',version=version+1,"
                + "updated_at=now()"
                + " where tenant_id=? and sku_id=? and version between ? and ?",
            actor.userId(), tenantId, skuId, input.version() - 1, input.version());
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "报关属性已被他人修改，请刷新后重试");
    audit(actor, skuId, "SkuCustomsAttributeSubmitted");
    return load(skuId);
  }

  /**
   * 审核：通过 / 驳回（需 {@code catalog.compliance.review} 权限）。
   *
   * <p><b>通过前钉死规则 A4</b>：至少一条申报要素 + 每条 required 的都有非空值。
   * 缺任何一条都会被 10 报关拿到一份不完整的资料，所以必须在<b>审核这一关</b>拦住，
   * 而不是等到报关单退单。
   *
   * <p><b>驳回必填原因</b>（DB CHECK 钉死）：驳回不写理由，运营就只能猜要怎么改。
   *
   * <p>审核通过后本属性内容冻结（DB 触发器 guard_customs_element_under_review），
   * 要改只能先驳回。
   */
  @Transactional
  public CustomsProfile review(
      AccessService.Access actor, UUID skuId, ReviewInput input, boolean reviewPermission) {
    // **权限判定先于取数**：审核是一个独立权限（能填 ≠ 能批），而取数要过 RLS。
    // 顺序反了的话，一个只有 read+update 的审核者拿到的会是
    // 「记录不存在或没有访问权限」404 —— 把「你没这个权限」说成「这行不存在」，
    // 运营会以为是自己查错了 SKU，而真正要改的是授权。
    CatalogService.check(reviewPermission, "没有审核权限：填 HS 码是日常录入，批准申报事实是另一回事");
    Record sku = skuRow(skuId);
    UUID tenantId = sku.get("tenant_id", UUID.class);
    Record attribute = attributeRow(skuId);
    CatalogService.check(attribute != null, "这个 SKU 还没有报关属性，先保存再提交审核");
    CatalogService.check(
        attribute.get("submitted_at", OffsetDateTime.class) != null,
        "先提交审核后才能审核：没有提交留痕就无法回答「这份资料是谁在什么时候交上来的」");

    if (input.approve()) {
      List<DeclarationElement> elements = elements(attribute.get("id", UUID.class));
      CatalogService.check(
          !elements.isEmpty(),
          "审核通过前必须至少填写一条申报要素：海关按申报要素逐项比对，缺项会被判为填报不符并退单");
      List<DeclarationElement> missing = missingRequired(elements);
      CatalogService.check(
          missing.isEmpty(),
          "审核通过前必须补齐必填申报要素（当前为空）："
              + missing.stream().map(e -> e.elementCode() + " " + e.elementName()).toList()
              + "。补齐后重新提交即可");
      sql.execute(
          "update customs_attribute set status='APPROVED',reviewed_by=?,reviewed_at=now(),"
              + "reject_reason='',version=version+1,updated_at=now()"
              + " where tenant_id=? and sku_id=?",
          actor.userId(), tenantId, skuId);
      audit(actor, skuId, "SkuCustomsAttributeApproved");
    } else {
      String reason = input.rejectReason() == null ? "" : input.rejectReason().trim();
      CatalogService.check(
          !reason.isEmpty(),
          "驳回必须写明原因：运营只能靠理由知道该怎么改，驳回不给理由就变成一次没有信息量的点击");
      sql.execute(
          "update customs_attribute set status='REJECTED',reviewed_by=?,reviewed_at=now(),"
              + "reject_reason=?,version=version+1,updated_at=now()"
              + " where tenant_id=? and sku_id=?",
          actor.userId(), reason, tenantId, skuId);
      audit(actor, skuId, "SkuCustomsAttributeRejected");
    }
    return load(skuId);
  }

  // ============================================================ 供 10 报关引用

  /**
   * 供 10 报关调用的申报资料 —— <b>本模块最重要的一条闸</b>（V94 第一节）。
   *
   * <p><b>未审核通过一律报错（409），而不是返回一份看起来能用的数据。</b>
   * 蓝图原文：「HS 编码错误直接导致申报失败与罚款，必须允许审核流程而非任意填写」，
   * 而 V85 写明「报关数据错误导致的扣货与罚款由卖家承担」——
   * 所以这里宁可让 10 报关拿不到，也不能让它拿到一份未经审核的 HS 码。
   *
   * <p>返回体与 V85 {@code customs_declaration_line} 的列一一对应，
   * 回填时不需要名称翻译（见 {@link DeclarationReady} 的类注释）。
   */
  public DeclarationReady declarationReady(UUID skuId) {
    CustomsProfile profile = load(skuId);
    if (!profile.declarationReady()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该 SKU 的报关属性尚未审核通过，不能用于报关（"
              + String.join("；", profile.blockerLabels())
              + "）。HS 编码错误会直接导致申报失败与罚款，"
              + "所以未审核通过的申报资料一律不外发");
    }
    List<DeclarationElement> elements = profile.elements();
    return new DeclarationReady(
        profile.skuId(),
        profile.skuCode(),
        profile.skuName(),
        profile.attributeId().toString(),
        profile.hsCode(),
        SOURCE_MODULE,
        true,
        profile.declaredName(),
        profile.originCountryCode(),
        profile.statutoryUnitCode(),
        new DeclarationElementsObject(elements, toByCode(elements)),
        SOURCE_MODULE,
        ELEMENT_STATUS_COMPLETE,
        profile.declaredValueReference(),
        Boolean.TRUE.equals(profile.preferentialOriginEligible()),
        profile.preferenceAgreement(),
        profile.reviewedBy(),
        profile.reviewedAt(),
        profile.note());
  }

  // ============================================================ 筛选

  /**
   * 按报关属性筛选（供 16 合规税则校验与 10 报关批量核）。
   *
   * <p><b>{@code preferenceAgreement=""} 是有意义的筛选</b>（筛「不适用任何协定」）：
   * 所以 {@link #normalizeAgreementFilter} 返回的空串会被保留而不是当成不筛。
   */
  public CustomsPage search(CustomsQuery query) {
    UUID tenantId = currentTenantId();
    int page = Math.max(0, query.page());
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);

    StringBuilder where = new StringBuilder(" where a.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);

    String keyword = normalizeKeyword(query.keyword());
    if (keyword != null) {
      where.append(" and (lower(s.code) like ? or lower(s.name) like ? or lower(a.declared_name) like ?)");
      String like = "%" + keyword + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String hsCode = normalizeHsCodeFilter(query.hsCode());
    if (hsCode != null) {
      where.append(" and a.hs_code=?");
      binds.add(hsCode);
    }
    if (query.originCountryCode() != null && !query.originCountryCode().trim().isEmpty()) {
      where.append(" and a.origin_country_code=?");
      binds.add(normalizeCountryCode(query.originCountryCode(), "原产地"));
    }
    String status = normalizeStatus(query.status());
    if (status != null) {
      where.append(" and a.status=?");
      binds.add(status);
    }
    String agreement = normalizeAgreementFilter(query.preferenceAgreement());
    if (agreement != null) {
      where.append(" and a.preference_agreement=?");
      binds.add(agreement);
    }
    if (query.preferentialOriginEligible() != null) {
      where.append(" and a.preferential_origin_eligible=?");
      binds.add(query.preferentialOriginEligible());
    }

    // LEFT JOIN 申报属性：筛「尚未维护」的 SKU 在筛选页里必须能出现在结果里。
    String from =
        " from sku s"
            + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
            + " left join customs_attribute a on a.tenant_id=s.tenant_id and a.sku_id=s.id";
    long total =
        sql.fetchOne("select count(*)" + from + where, binds.toArray()).get(0, Long.class);
    if (total == 0) return new CustomsPage(List.of(), page, size, 0, 0);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            "select s.id,s.code,s.name,p.code as spu_code,a.id as attribute_id,a.hs_code,"
                + "a.origin_country_code,a.declared_name,a.declared_value_reference,"
                + "a.statutory_unit_code,a.preferential_origin_eligible,a.preference_agreement,"
                + "a.status,a.submitted_at,"
                + "(select count(*) from customs_declaration_element e"
                + " where e.tenant_id=a.tenant_id and e.customs_attribute_id=a.id) as element_count,"
                + "(select count(*) from customs_declaration_element e"
                + " where e.tenant_id=a.tenant_id and e.customs_attribute_id=a.id"
                + " and e.required and btrim(e.element_value)='') as required_missing_count,"
                + "coalesce(a.updated_at,s.updated_at) as profile_updated_at"
                + from
                + where
                + " order by profile_updated_at desc nulls last,s.id desc"
                + " limit ? offset ?",
            pageBinds.toArray());

    List<CustomsRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) {
      items.add(
          new CustomsRow(
              r.get("id", UUID.class),
              r.get("code", String.class),
              r.get("name", String.class),
              r.get("spu_code", String.class),
              blankToEmpty(r.get("hs_code", String.class)),
              blankToEmpty(r.get("origin_country_code", String.class)),
              blankToEmpty(r.get("declared_name", String.class)),
              r.get("declared_value_reference", BigDecimal.class),
              blankToEmpty(r.get("statutory_unit_code", String.class)),
              Boolean.TRUE.equals(r.get("preferential_origin_eligible", Boolean.class)),
              blankToEmpty(r.get("preference_agreement", String.class)),
              r.get("status", String.class),
              r.get("element_count", Integer.class),
              r.get("required_missing_count", Integer.class),
              instant(r.get("submitted_at", OffsetDateTime.class)),
              instant(r.get("profile_updated_at", OffsetDateTime.class))));
    }
    return new CustomsPage(
        List.copyOf(items), page, size, total, (int) ((total + size - 1) / size));
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  // ============================================================ 公共

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }
}