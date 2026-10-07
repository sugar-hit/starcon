package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.4.3.1 多语言内容的批量操作：补齐待翻占位、批量采纳 AI 建议、批量标记已校对。
 *
 * <p><b>同步执行，没有「排队中」这种自欺欺人的状态</b>（与 {@code TitleService} 的标题生成、
 * {@code DescriptionRenderService} 的批量填充同一判断）：三件事都是几百行的短事务，
 * 做成异步只会让运营为几百毫秒去盯进度条。所以
 * {@code status} 只有 SUCCEEDED / PARTIAL / FAILED 三态。
 *
 * <p><b>失败与跳过是一等公民，逐条落库而不是只回一个计数。</b>
 * 「这批 50 个商品的德语，有 8 个因为已有译文被跳过」要能逐条回答——
 * 逐行 list 才有 {@code translation_batch_item}，批次的三个计数只是它的汇总。
 *
 * <p>三件事的语义各不相同，所以拒绝理由也各不相同：
 *
 * <ul>
 *   <li>{@code CREATE_PENDING}：只在**还没有任何译文行**时补占位。已有译文（不管什么状态）
 *       一律跳过——把一条已翻好的德语译文重建成待翻会抹掉工作成果。
 *   <li>{@code ADOPT_SUGGESTIONS}：按 {@code batchRef} 找待处置建议逐条采纳。
 *       没有建议的 (商品, 语言) 组合拒收并写明「18 还没交这语言的建议」。
 *   <li>{@code MARK_REVIEWED}：只把**已翻**的行标成已校对。待翻的拒收（正文都没有，
 *       无从校对）；已校对的跳过（不需要重复校对）。
 * </ul>
 */
@Service
public class TranslationBatchService {
  private final DSLContext sql;
  private final TranslationService translations;

  public TranslationBatchService(DSLContext sql, TranslationService translations) {
    this.sql = sql;
    this.translations = translations;
  }

  // ============================================================ 提交

  /**
   * 执行一次批量操作。
   *
   * <p>提交时选中的商品数写进 {@code item_count}（不用 count）：列表页要显示 N/N，
   * 而 count 会随待翻占位行被补出来而变化。
   */
  public TranslationBatchDetail run(AccessService.Access actor, TranslationBatchInput input) {
    String action = MediaRules.normalizeUpper(input.action());
    String problem = TranslationRules.batchActionProblem(action);
    check(problem == null, problem);
    String type = TranslationService.checkedType(input.targetType());
    String batchRef = MediaRules.normalize(input.batchRef());
    problem = TranslationRules.batchRefProblem(batchRef);
    check(problem == null, problem);

    UUID batchId = Ids.next();
    OffsetDateTime now = TranslationService.utcNow();
    List<Item> planned = plan(actor, action, type, input, batchRef);
    // 整批没有一条可执行时才拒收：逐条都有结果（全跳过 / 全未执行）的批次照样要留下
    // 「为什么一条都没成」的逐条记录，而不是回一个 0 成功的汇总。
    if (planned.stream().noneMatch(Item::actionable)) {
      String reason =
          switch (action) {
            case "CREATE_PENDING" -> "选中的商品在指定语言下都已有译文行，没有需要补的「待翻」占位";
            case "ADOPT_SUGGESTIONS" -> "没有找到待处置的翻译建议：18 AI 翻译还没交这一批，"
                + "或者 batchRef 写错了（已交的建议请在建议列表里核对 batch_ref）";
            default -> "选中的商品在指定语言下都没有「已翻」的译文，没有需要标校对的行";
          };
      throw TranslationService.conflict(reason);
    }

    int succeeded = 0;
    int skipped = 0;
    int rejected = 0;
    for (Item item : planned) {
      if (item.actionable()) succeeded++;
      else if (item.rejected()) rejected++;
      else skipped++;
    }
    int total = planned.size();
    String status = rejected == 0 && skipped == 0 ? "SUCCEEDED" : succeeded == 0 ? "FAILED" : "PARTIAL";

    // 先写批次再写逐条结果：translation_batch_item 有指向批次的复合外键，
    // 反过来写会当场撞 FK，而 counts 是 plan() 早就算出来的，不需要先执行。
    sql.execute(
        "insert into translation_batch(id,tenant_id,action,requested_by,target_type,item_count,"
            + "locale_count,succeeded_count,skipped_count,rejected_count,status,error,created_at,"
            + "finished_at,version) values(?,?,?,?,?,?,?,?,?,?,?,'',?::timestamptz,?::timestamptz,0)",
        batchId,
        actor.tenantId(),
        action,
        actor.userId(),
        type,
        total,
        (int) planned.stream().map(Item::locale).distinct().count(),
        succeeded,
        skipped,
        rejected,
        status,
        now,
        now);

    for (Item item : planned) {
      String outcome;
      String reason;
      if (item.actionable()) {
        outcome = "SUCCEEDED";
        reason = "";
        apply(actor, action, type, item);
      } else {
        outcome = item.rejected() ? "REJECTED" : "SKIPPED";
        reason = item.reason();
      }
      sql.execute(
          "insert into translation_batch_item(id,tenant_id,batch_id,target_type,spu_id,sku_id,locale,"
              + "outcome,reason) values(?,?,?,?,?,?,?,?,?)",
          Ids.next(), actor.tenantId(), batchId, type, item.spuId(), item.skuId(), item.locale(),
          outcome, reason);
    }
    translations.audit(actor, batchId, "TranslationBatch:" + action + ":" + status);
    return detail(batchId);
  }

  // ============================================================ 计划：把提交展开成逐条 (商品, 语言)

  /** 一条待执行项。{@code actionable=false} 时 {@code reason} 是给人看的中文原因。 */
  private record Item(UUID spuId, UUID skuId, String locale, boolean actionable, boolean rejected,
      String reason) {}

  private List<Item> plan(
      AccessService.Access actor,
      String action,
      String type,
      TranslationBatchInput input,
      String batchRef) {
    String table = TranslationService.tableOf(type);
    String column = TranslationService.subjectColumnOf(type);
    String productTable = "SKU".equals(type) ? "sku" : "spu";

    // ADOPT_SUGGESTIONS 按 batchRef 找建议，不看 targetIds：
    // 18 交来一批建议时运营手上通常只有 batchRef，商品清单可能还没筛。
    if ("ADOPT_SUGGESTIONS".equals(action)) {
      check(!batchRef.isEmpty(),
          "批量采纳 AI 建议必须给 batchRef：那 18 AI 翻译一次交回的一批建议用 batch_ref 归组，"
              + "不给就没法确定「这一批」是哪几条");
      List<Record> rows =
          sql.fetch(
              "select " + column + " as subject,locale from translation_suggestion"
                  + " where tenant_id=? and status='PENDING' and batch_ref=?"
                  + " order by created_at,id",
              actor.tenantId(), batchRef);
      if (rows.isEmpty()) return List.of();
      List<Item> out = new ArrayList<>(rows.size());
      for (Record r : rows) {
        UUID subject = r.get("subject", UUID.class);
        String locale = r.get("locale", String.class);
        // 商品可能已被删（FK 会级联删建议，但这一行可能是刚被删的竞态）。
        boolean exists =
            sql.fetchOne(
                    "select 1 from " + productTable + " where tenant_id=? and id=?", actor.tenantId(), subject)
                != null;
        out.add(
            new Item(
                "SPU".equals(type) ? subject : null,
                "SKU".equals(type) ? subject : null,
                locale,
                exists,
                !exists,
                exists ? "" : "建议指向的商品已不存在（FK 级联删除这一行时刚好撞上并发），"
                    + "请让 18 AI 翻译跳过这个商品后重新提交"));
      }
      return out;
    }

    List<String> rawIds = input.targetIds() == null ? List.of() : input.targetIds();
    check(TranslationRules.itemsSizeProblem(rawIds.size()) == null,
        TranslationRules.itemsSizeProblem(rawIds.size()));
    List<UUID> ids = new ArrayList<>();
    for (String raw : rawIds) ids.add(TranslationService.requireUuid(raw, "targetIds"));
    Set<UUID> distinct = new LinkedHashSet<>(ids);
    check(distinct.size() == ids.size(), "targetIds 里有重复的商品 id：一个商品在一批里只出现一次");

    List<String> locales = new ArrayList<>(translations.normalizeLocales(input.locales()));
    if (locales.isEmpty()) locales = new ArrayList<>(translations.requiredLocales());
    if ("CREATE_PENDING".equals(action))
      check(TranslationRules.batchLocalesSizeProblem(locales.size()) == null,
          TranslationRules.batchLocalesSizeProblem(locales.size()));

    if ("CREATE_PENDING".equals(action) && locales.isEmpty())
      throw TranslationService.conflict(
          "租户还没有登记任何语言需求，所以推不出「要补哪些语言的待翻占位」。"
              + "请先在「语言需求」里登记租户实际需要的语言，或在这次提交里显式指定 locales");
    if ("MARK_REVIEWED".equals(action) && locales.isEmpty())
      throw TranslationService.conflict(
          "租户还没有登记任何语言需求，所以推不出「哪些语言的译文可以标校对」。"
              + "请先在「语言需求」里登记租户实际需要的语言，或在这次提交里显式指定 locales");

    Set<String> productIds = new LinkedHashSet<>();
    for (Record r :
        sql.fetch(
            "select id from " + productTable + " where tenant_id=? and id in ("
                + placeholders(distinct.size()) + ")",
            concat(actor.tenantId(), distinct)))
      productIds.add(r.get("id", UUID.class).toString());

    List<Item> out = new ArrayList<>();
    for (String rawId : rawIds) {
      UUID id = UUID.fromString(rawId);
      for (String locale : locales) {
        var existing =
            sql.fetchOne(
                "select status from " + table + " where tenant_id=? and " + column + "=? and locale=?",
                actor.tenantId(), id, locale);
        if ("CREATE_PENDING".equals(action)) {
          if (existing != null) {
            String current = existing.get("status", String.class);
            out.add(new Item(
                "SPU".equals(type) ? id : null,
                "SKU".equals(type) ? id : null,
                locale,
                false,
                false,
                "已经有「" + TranslationRules.statusLabel(current) + "」的译文行："
                    + "重建待翻占位会抹掉已有工作。要重新开始请先删掉那一行"));
          } else if (!productIds.contains(rawId)) {
            out.add(new Item(
                "SPU".equals(type) ? id : null, "SKU".equals(type) ? id : null, locale, false, true,
                "商品不存在或不属于当前租户"));
          } else {
            out.add(new Item(
                "SPU".equals(type) ? id : null, "SKU".equals(type) ? id : null, locale, true, false, ""));
          }
        } else {
          if (existing == null)
            out.add(new Item(
                "SPU".equals(type) ? id : null, "SKU".equals(type) ? id : null, locale, false, true,
                "这个商品在语言「" + locale + "」下还没有译文行：没有文本就无从校对，"
                    + "请先补齐待翻占位并填入译文"));
          else {
            String current = existing.get("status", String.class);
            if ("REVIEWED".equals(current))
              out.add(new Item(
                  "SPU".equals(type) ? id : null, "SKU".equals(type) ? id : null, locale, false, false,
                  "已经是「已校对」，不需要重复标校对"));
            else if (!"TRANSLATED".equals(current))
              out.add(new Item(
                  "SPU".equals(type) ? id : null, "SKU".equals(type) ? id : null, locale, false, true,
                  "当前是「" + TranslationRules.statusLabel(current) + "」：待翻没有正文，无从校对"));
            else if (!productIds.contains(rawId))
              out.add(new Item(
                  "SPU".equals(type) ? id : null, "SKU".equals(type) ? id : null, locale, false, true,
                  "商品不存在或不属于当前租户"));
            else
              out.add(new Item(
                  "SPU".equals(type) ? id : null, "SKU".equals(type) ? id : null, locale, true, false, ""));
          }
        }
      }
    }
    return out;
  }

  // ============================================================ 执行

  private void apply(
      AccessService.Access actor, String action, String type, Item item) {
    String table = TranslationService.tableOf(type);
    String column = TranslationService.subjectColumnOf(type);
    UUID subject = item.spuId() != null ? item.spuId() : item.skuId();
    OffsetDateTime now = TranslationService.utcNow();
    if ("ADOPT_SUGGESTIONS".equals(action)) {
      var row =
          sql.fetchOne(
              "select id from translation_suggestion where tenant_id=? and status='PENDING'"
                  + " and " + column + "=? and locale=? order by created_at,id limit 1",
              actor.tenantId(), subject, item.locale());
      if (row == null)
        throw TranslationService.conflict(
            "这条建议刚被别人处置了（语言「" + item.locale() + "」）：请刷新后重试");
      translations.adoptSuggestion(actor, row.get("id", UUID.class));
      return;
    }
    if ("CREATE_PENDING".equals(action)) {
      sql.execute(
          "insert into " + table + "(id,tenant_id," + column + ",locale,name,description,origin,status,"
              + "version) values(?,?,?,?,'','','MANUAL','PENDING',0)",
          Ids.next(), actor.tenantId(), subject, item.locale());
      return;
    }
    int affected =
        sql.execute(
            "update " + table + " set status='REVIEWED',reviewed_by=?,reviewed_at=?::timestamptz,"
                + "version=version+1,updated_at=now() where tenant_id=? and " + column
                + "=? and locale=? and status='TRANSLATED'",
            actor.userId(), now, actor.tenantId(), subject, item.locale());
    if (affected != 1)
      throw TranslationService.conflict(
          "这条译文刚被别人改过（语言「" + item.locale() + "」）：请刷新后重试");
  }

  // ============================================================ 批次查询

  /** 批次列表：分页 + 动作 + 主体类型 + 状态。 */
  public TranslationBatchPage batches(TranslationBatchQuery query) {
    int page = Math.max(0, query.page());
    int size = TranslationService.clampSize(query.size());
    StringBuilder where = new StringBuilder(" where tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    String action = MediaRules.normalizeUpper(query.action());
    if (!action.isEmpty()) {
      String problem = TranslationRules.batchActionProblem(action);
      check(problem == null, problem);
      where.append(" and action=?");
      binds.add(action);
    }
    String type = MediaRules.normalizeUpper(query.targetType());
    if (!type.isEmpty()) {
      String problem = TranslationRules.targetTypeProblem(type);
      check(problem == null, problem);
      where.append(" and target_type=?");
      binds.add(type);
    }
    String status = MediaRules.normalizeUpper(query.status());
    if (!status.isEmpty()) {
      if (!TranslationRules.BATCH_STATUSES.contains(status))
        throw MediaRules.bad("批次状态只能是 " + String.join(" / ", TranslationRules.BATCH_STATUSES));
      where.append(" and status=?");
      binds.add(status);
    }
    long total =
        sql.fetchOne("select count(*) from translation_batch" + where, binds.toArray())
            .get(0, Long.class);
    if (total == 0) return new TranslationBatchPage(List.of(), page, size, 0, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            "select * from translation_batch" + where
                + " order by created_at desc,id desc limit ? offset ?",
            pageBinds.toArray());
    List<TranslationBatchRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(toBatch(r));
    return new TranslationBatchPage(items, page, size, total, (int) Math.ceil(total / (double) size));
  }

  /** 批次详情：批次 + 逐条结果（含目标编码，页面逐条核对用）。 */
  public TranslationBatchDetail detail(UUID batchId) {
    var row = sql.fetchOne("select * from translation_batch where tenant_id=? and id=?", currentTenantId(), batchId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批量操作批次不存在");
    var items =
        sql.fetch(
            "select i.*,coalesce(sp.code,'') as spu_code,coalesce(sp.name,'') as spu_name,"
                + "coalesce(sk.code,'') as sku_code,coalesce(sk.name,'') as sku_name"
                + " from translation_batch_item i"
                + " left join spu sp on sp.tenant_id=i.tenant_id and sp.id=i.spu_id"
                + " left join sku sk on sk.tenant_id=i.tenant_id and sk.id=i.sku_id"
                + " where i.tenant_id=? and i.batch_id=? order by i.outcome desc,i.id",
            currentTenantId(), batchId);
    List<TranslationBatchItemRow> out = new ArrayList<>(items.size());
    for (Record r : items) out.add(toBatchItem(r));
    TranslationBatchRow batch = toBatch(row);
    return new TranslationBatchDetail(
        batch,
        out,
        TranslationRules.batchActionLabel(batch.action()) + "：成功 "
            + batch.succeededCount() + " 条、跳过 " + batch.skippedCount() + " 条、未执行 "
            + batch.rejectedCount() + " 条。逐条原因在下面，不是一个汇总数字——"
            + "「哪几个没成」是批量操作唯一有用的输出。");
  }

  private TranslationBatchRow toBatch(Record r) {
    String action = r.get("action", String.class);
    String status = r.get("status", String.class);
    return new TranslationBatchRow(
        r.get("id", UUID.class),
        action,
        TranslationRules.batchActionLabel(action),
        r.get("target_type", String.class),
        r.get("requested_by", UUID.class),
        r.get("item_count", Integer.class),
        r.get("locale_count", Integer.class),
        r.get("succeeded_count", Integer.class),
        r.get("skipped_count", Integer.class),
        r.get("rejected_count", Integer.class),
        status,
        TranslationRules.batchStatusLabel(status),
        r.get("error", String.class),
        r.get("version", Long.class),
        TranslationService.instant(r.get("created_at", OffsetDateTime.class)),
        TranslationService.instant(r.get("finished_at", OffsetDateTime.class)));
  }

  private TranslationBatchItemRow toBatchItem(Record r) {
    String outcome = r.get("outcome", String.class);
    boolean sku = "SKU".equals(r.get("target_type", String.class));
    return new TranslationBatchItemRow(
        r.get("id", UUID.class),
        r.get("target_type", String.class),
        r.get("spu_id", UUID.class),
        r.get("sku_id", UUID.class),
        sku ? r.get("sku_code", String.class) : r.get("spu_code", String.class),
        sku ? r.get("sku_name", String.class) : r.get("spu_name", String.class),
        r.get("locale", String.class),
        outcome,
        TranslationRules.outcomeLabel(outcome),
        r.get("reason", String.class),
        TranslationService.instant(r.get("created_at", OffsetDateTime.class)));
  }

  // ============================================================ 内部

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  static void check(boolean ok, String message) {
    if (!ok) throw MediaRules.bad(message);
  }

  static String placeholders(int size) {
    return String.join(",", java.util.Collections.nCopies(size, "?"));
  }

  static Object[] concat(UUID tenantId, Set<UUID> ids) {
    Object[] out = new Object[ids.size() + 1];
    out[0] = tenantId;
    int i = 1;
    for (UUID id : ids) out[i++] = id;
    return out;
  }
}