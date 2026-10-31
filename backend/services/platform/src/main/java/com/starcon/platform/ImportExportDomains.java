package com.starcon.platform;

import static com.starcon.platform.ImportExportModels.ImportExportColumn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 18.1.3.1「导入导出任务」的**数据域目录**。
 *
 * <p>蓝图原文：「<b>关系</b>：上游 = 全部业务域；下游 = 用户本地文件。」
 *
 * <p>🔴 <b>「全部业务域」不等于「一条 SQL join 七个服务的表」</b>。
 * 根 AGENTS.md 的模块边界纪律写死：「跨 {@code backend/services/<name>/} 必须走授权 API / event，
 * 禁止跨服务 SQL / FK / 共享视图」。所以本目录把「全部业务域」拆成<b>三条通道</b>，
 * 每一格都写明「数据从哪来」与「没接通的缺在谁身上」：
 *
 * <ul>
 *   <li><b>通道 A · LOCAL_TABLE</b> —— 数据域的事实<b>本来就住在 platform</b>：
 *       应用与凭证、事件订阅、投递记录、调用埋点。本模块用 jOOQ 显式 SQL 读自己的表。
 *   <li><b>通道 B · AUTHORIZED_PORT</b> —— 数据域住在<b>属主服务</b>（catalog / trade /
 *       warehouse / channel / finance）。本模块只经属主服务<b>自己的授权 API</b> 读，
 *       <b>只转发调用方自己的 bearer</b>（不替对方决定租户），<b>不抄一份到本库</b>
 *       —— 抄一份就会漂移（属主那边改了，本库还留着旧的，导出的文件与真实账对不上）。
 *   <li><b>通道 C · DELEGATED_JOB</b> —— <b>导入要写业务域，必须由属主写</b>。
 *       商品域的导入作业在 catalog（文件型 multipart 作业 + 逐行错误），
 *       本模块负责转发文件、轮询作业、把逐行错误回灌成自己的失败行报告。
 * </ul>
 *
 * <p>🔴 <b>没接通的格子如实标 {@code available=false}</b>，并写明缺什么、归谁 ——
 * 照抄 platform 的 {@code UnavailableFinanceSoftwarePushPort}：
 * 「一条返回成功结果的假通道，会让『凭证已经推给金蝶了』在数据上看起来发生过，
 * 而现场一个字都没推出去」。**绝不为让测试绿而写成假的 {@code true}。**
 */
public final class ImportExportDomains {

  private ImportExportDomains() {}

  // ================================================================ 来源词表

  /** 导出数据从哪来。 */
  public enum ExportSource {
    /** 数据域的事实住在 platform 本库（本模块用 jOOQ 显式 SQL 读自己的表）。 */
    LOCAL_TABLE("platform 自有表"),
    /** 数据域住在属主服务；本模块经它的**授权只读 API** 读（只转发调用方自己的 bearer）。 */
    AUTHORIZED_PORT("属主服务授权只读端点"),
    /** 没接通。 */
    NONE("未接通");

    private final String label;

    ExportSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 导入数据往哪写。 */
  public enum ImportSource {
    /** 数据域的事实住在 platform 本库；本模块**自己**校验并落库。 */
    LOCAL_APPLY("platform 自有表（通道内落库）"),
    /** 数据域住在属主服务；本模块把文件**委托**给属主的文件型导入作业执行。 */
    DELEGATED_JOB("属主服务文件型导入作业"),
    /** 没接通。 */
    NONE("未接通");

    private final String label;

    ImportSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 范围字段

  /**
   * 一个范围字段（任务创建时选择「导哪一段」）。
   *
   * @param fieldKey 程序内稳定标识
   * @param label 中文名
   * @param dataType 数据类型
   * @param hint 填写提示
   */
  public record ScopeField(String fieldKey, String label, String dataType, String hint) {}

  // ================================================================ 数据域

  /**
   * 一个数据域。
   *
   * @param code 稳定标识
   * @param label 中文名
   * @param ownerService 属主服务
   * @param ownerModule 归属模块（蓝图里的 L3 / L4 编号）
   * @param exportSource 导出来源
   * @param exportColumns 导出列
   * @param exportScope 可选的范围字段
   * @param exportReason 导出没接通时的原因（接通时为空串）
   * @param exportLimitation 接通时的边界
   * @param importSource 导入去向
   * @param importColumns 导入列
   * @param importReason 导入没接通时的原因（接通时为空串）
   */
  public record Domain(
      String code,
      String label,
      String ownerService,
      String ownerModule,
      ExportSource exportSource,
      List<ImportExportColumn> exportColumns,
      List<ScopeField> exportScope,
      String exportReason,
      String exportLimitation,
      ImportSource importSource,
      List<ImportExportColumn> importColumns,
      String importReason) {}

  // ================================================================ 列定义工具

  private static ImportExportColumn col(String key, String header, String type) {
    return new ImportExportColumn(key, header, type, false, "", "");
  }

  private static ImportExportColumn req(
      String key, String header, String type, String sample, String help) {
    return new ImportExportColumn(key, header, type, true, sample, help);
  }

  private static ScopeField scope(String key, String label, String type, String hint) {
    return new ScopeField(key, label, type, hint);
  }

  // ================================================================ 目录本体

  /**
   * 全部数据域。
   *
   * <p>🔴 **顺序即分组**：先是 platform 自有的四个域（通道 A），
   * 再是属主服务的五个域（通道 B / C），最后是**如实标未接通**的域。
   * 页面按这个顺序渲染 ⇒ 「哪些还没接通」一眼看得见，不会被混在可用项里。
   */
  public static final List<Domain> ALL =
      List.of(
          // ---------------------------------------------------------- 通道 A：platform 自有表
          new Domain(
              "PLATFORM_APP",
              "平台应用与凭证",
              "platform",
              "18.1.1.1",
              ExportSource.LOCAL_TABLE,
              List.of(
                  col("app_code", "应用编码", "STRING"),
                  col("app_name", "应用名称", "STRING"),
                  col("app_type", "应用类型", "STRING"),
                  col("status", "状态", "STRING"),
                  col("description", "说明", "STRING"),
                  col("contact_name", "联系人", "STRING"),
                  col("contact_email", "联系邮箱", "STRING"),
                  col("created_at", "创建时间", "DATETIME")),
              List.of(
                  scope("status", "状态", "STRING", "留空表示不限"),
                  scope("q", "关键字", "STRING", "匹配应用编码或名称")),
              "",
              "导出的是**应用档案**（不含任何密钥明文 —— 库里根本没有明文）",
              ImportSource.NONE,
              List.of(),
              "应用档案的导入要同时处理 API Key / Secret 的签发与轮换（18.1.1.1 的职责），"
                  + "批量导入的应用拿不到可用凭证 —— 通道只提供导出，导入走应用档案页逐个注册。"),
          new Domain(
              "PLATFORM_SUBSCRIPTION",
              "事件订阅",
              "platform",
              "18.1.2.1",
              ExportSource.LOCAL_TABLE,
              List.of(
                  col("name", "订阅名称", "STRING"),
                  col("target_url", "订阅端点地址", "STRING"),
                  col("status", "状态", "STRING"),
                  col("app_code", "所属应用", "STRING"),
                  col("event_types", "订阅事件类型", "STRING"),
                  col("description", "说明", "STRING"),
                  col("created_at", "创建时间", "DATETIME")),
              List.of(
                  scope("status", "状态", "STRING", "留空表示不限"),
                  scope("q", "关键字", "STRING", "匹配名称或端点地址")),
              "",
              "导出的是**订阅配置**（不导出签名密钥 —— 它是密文，且轮换后旧密文即刻作废）",
              ImportSource.LOCAL_APPLY,
              List.of(
                  req("name", "订阅名称", "STRING", "订单事件订阅", "同一个应用下不可重名"),
                  req("target_url", "订阅端点地址", "STRING", "https://example.com/hook",
                      "必须以 http:// 或 https:// 开头"),
                  req("app_code", "所属应用", "STRING", "erp-connector", "必须是本租户已注册的应用编码"),
                  req("event_types", "订阅事件类型", "STRING", "order.created|order.paid",
                      "多个用 | 分隔；每个都必须在本租户已登记的事件类型里"),
                  new ImportExportColumn(
                      "description", "说明", "STRING", false, "对接方 ERP 接收订单事件", "选填")),
              ""),
          new Domain(
              "PLATFORM_DELIVERY",
              "事件投递记录",
              "platform",
              "18.1.2.2",
              ExportSource.LOCAL_TABLE,
              List.of(
                  col("event_type", "事件类型", "STRING"),
                  col("schema_version", "事件版本", "NUMBER"),
                  col("status", "投递状态", "STRING"),
                  col("status_code", "响应码", "NUMBER"),
                  col("attempt_count", "尝试次数", "NUMBER"),
                  col("replay_count", "重放次数", "NUMBER"),
                  col("target_url", "目标地址", "STRING"),
                  col("last_error", "最近错误", "STRING"),
                  col("created_at", "创建时间", "DATETIME"),
                  col("delivered_at", "送达时间", "DATETIME")),
              List.of(
                  scope("status", "投递状态", "STRING", "留空表示不限"),
                  scope("event_type", "事件类型", "STRING", "留空表示不限")),
              "",
              "导出的是**投递明细**（不含请求体 —— 请求体里可能带买家 PII）",
              ImportSource.NONE,
              List.of(),
              "投递记录是**执行事实**：每一次投递占一行。导入一份投递记录等于伪造「已经投递过」—— "
                  + "对账时会以为对方收到了事件。投递记录只可导出核对，不可导入。"),
          new Domain(
              "PLATFORM_CALL_LOG",
              "API 调用埋点",
              "platform",
              "18.1.1.3",
              ExportSource.LOCAL_TABLE,
              List.of(
                  col("endpoint_code", "接口编码", "STRING"),
                  col("http_method", "方法", "STRING"),
                  col("request_path", "请求路径", "STRING"),
                  col("status_code", "响应码", "NUMBER"),
                  col("outcome", "结论", "STRING"),
                  col("error_code", "错误码", "STRING"),
                  col("rate_limited", "被限流", "BOOLEAN"),
                  col("duration_ms", "耗时（毫秒）", "NUMBER"),
                  col("request_id", "请求号", "STRING"),
                  col("called_at", "发生时间", "DATETIME")),
              List.of(
                  scope("outcome", "结论", "STRING", "留空表示不限"),
                  scope("endpoint_code", "接口编码", "STRING", "留空表示不限")),
              "",
              "导出的是**调用埋点**（不含调用方 IP 与密钥摘要）",
              ImportSource.NONE,
              List.of(),
              "调用埋点是**只追加**的事实（V414 的触发器连 UPDATE/DELETE 都拒绝），"
                  + "导入等于伪造调用历史 —— 监控页上的 QPS 与错误率会变成假的。埋点只可导出，不可导入。"),
          // ---------------------------------------------------------- 通道 B：属主服务授权只读端点
          new Domain(
              "PRODUCT",
              "商品与 SKU",
              "catalog",
              "3.1.2",
              ExportSource.AUTHORIZED_PORT,
              List.of(
                  col("sku_code", "SKU 编码", "STRING"),
                  col("sku_name", "SKU 名称", "STRING"),
                  col("spu_code", "SPU 编码", "STRING"),
                  col("spu_name", "SPU 名称", "STRING"),
                  col("category", "类目", "STRING"),
                  col("barcode", "条码", "STRING"),
                  col("base_unit", "基本单位", "STRING"),
                  col("status", "状态", "STRING"),
                  col("variant_signature", "规格签名", "STRING"),
                  col("updated_at", "更新时间", "DATETIME")),
              List.of(
                  scope("q", "关键字", "STRING", "匹配编码 / 名称 / 条码"),
                  scope("category", "类目", "STRING", "留空表示不限"),
                  scope("status", "状态", "STRING", "留空表示不限")),
              "",
              "经 catalog 的授权只读端点 {@code GET /catalog/skus} 逐页拉取；"
                  + "**不在 platform 落一份副本**（属主改了这边就漂）",
              ImportSource.DELEGATED_JOB,
              List.of(),
              ""),
          new Domain(
              "ORDER",
              "销售订单",
              "trade",
              "9.1",
              ExportSource.AUTHORIZED_PORT,
              List.of(
                  col("order_no", "订单号", "STRING"),
                  col("order_source", "订单来源", "STRING"),
                  col("currency", "币种", "STRING"),
                  col("grand_total_amount", "订单总额", "NUMBER"),
                  col("line_count", "行数", "NUMBER"),
                  col("submitted_at", "下单时间", "DATETIME")),
              List.of(
                  scope("ordered_from", "下单起始日", "DATE", "含当天"),
                  scope("ordered_to", "下单截止日", "DATE", "含当天")),
              "",
              "经 trade 的授权只读端点 {@code GET /trade/sales-orders/list} 逐页拉取",
              ImportSource.NONE,
              List.of(),
              "trade 没有面向通道的**文件型**导入作业端点。而且订单不是可以「导进去」的数据 —— "
                  + "下单要占库存、要过信用校验、要触发平台回传；导入订单等于绕过这三条。"
                  + "订单的补录走 9.x 自己的处置流程。"),
          new Domain(
              "INVENTORY",
              "库存头寸",
              "warehouse",
              "7.1",
              ExportSource.AUTHORIZED_PORT,
              List.of(
                  col("sku_code", "SKU 编码", "STRING"),
                  col("sku_name", "SKU 名称", "STRING"),
                  col("warehouse_code", "仓库编码", "STRING"),
                  col("warehouse_name", "仓库名称", "STRING"),
                  col("legal_entity_code", "法人主体", "STRING"),
                  col("on_hand", "在手数量", "NUMBER"),
                  col("reserved", "预留数量", "NUMBER"),
                  col("available", "可用数量", "NUMBER"),
                  col("zero_stock", "零库存", "BOOLEAN"),
                  col("overstock", "超储", "BOOLEAN")),
              List.of(),
              "",
              "经 warehouse 的授权只读端点 {@code GET /warehouse/inventory/overview} 逐页拉取",
              ImportSource.NONE,
              List.of(),
              "库存是**由单据推导出来的余量**（入库 / 出库 / 调拨 / 盘点），不是可以「导进去」的数据 —— "
                  + "直接写库存会把账实关系写坏，让后续单据算出的可用量全错。"
                  + "仓库侧唯一的批量导入是**序列号**导入（{@code /warehouse/inventory/serials/import}），"
                  + "那是另一个数据域（序列号档案），不在本模块的范围内。"),
          new Domain(
              "LISTING",
              "平台商品",
              "channel",
              "4.1",
              ExportSource.AUTHORIZED_PORT,
              List.of(
                  col("shop_code", "店铺编码", "STRING"),
                  col("shop_name", "店铺名称", "STRING"),
                  col("platform_code", "平台", "STRING"),
                  col("marketplace_code", "站点", "STRING"),
                  col("external_item_id", "平台商品 ID", "STRING"),
                  col("title", "标题", "STRING"),
                  col("status", "状态", "STRING"),
                  col("mapping_status", "映射状态", "STRING")),
              List.of(),
              "",
              "经 channel 的授权只读端点 {@code GET /channel/listings} 逐页拉取",
              ImportSource.NONE,
              List.of(),
              "平台商品的写入归 channel 的发布流程（4.3.1.3）：发布要过平台侧校验、限流与回执对账，"
                  + "绕开它把 listing 导进去，会造出平台侧不存在的刊登。"),
          new Domain(
              "VOUCHER",
              "会计凭证",
              "finance",
              "12.5.2.1",
              ExportSource.AUTHORIZED_PORT,
              List.of(
                  col("voucher_no", "凭证号", "STRING"),
                  col("period_code", "会计期间", "STRING"),
                  col("voucher_date", "凭证日期", "DATE"),
                  col("currency", "币种", "STRING"),
                  col("status", "状态", "STRING"),
                  col("description", "摘要", "STRING"),
                  col("total_debit", "借方合计", "NUMBER"),
                  col("total_credit", "贷方合计", "NUMBER"),
                  col("line_count", "分录行数", "NUMBER")),
              List.of(
                  scope("status", "凭证状态", "STRING", "留空表示不限"),
                  scope("date_from", "起始日", "DATE", "含当天"),
                  scope("date_to", "截止日", "DATE", "含当天"),
                  scope("currency", "币种", "STRING", "留空表示不限")),
              "",
              "经 finance 的授权只读端点 {@code GET /finance/vouchers} 逐页拉取",
              ImportSource.NONE,
              List.of(),
              "凭证是**会计事实**：导入一份凭证等于凭空记账，试算平衡表会与银行流水对不上。"
                  + "finance 侧也没有面向通道的凭证导入端点。"),
          // ---------------------------------------------------------- 如实标未接通
          new Domain(
              "CUSTOMER",
              "客户与联系人",
              "crm",
              "15.1",
              ExportSource.NONE,
              List.of(),
              List.of(),
              "crm 服务目前**没有面向平台的客户档案列表授权端点**"
                  + "（只有 {@code /crm/customer-tags}、{@code /crm/buyer-emails} 等零散端点，"
                  + "没有「客户档案 + 联系人」的可分页列表）。"
                  + "在 crm 补上这个只读端点之前，这一格如实标为未接通，"
                  + "而不是导出一份空文件冒充「没有客户」。",
              "",
              ImportSource.NONE,
              List.of(),
              "同上：crm 没有面向通道的客户档案写入端点（客户档案要走去重、合并与合规校验）。"));

  // ================================================================ 查找

  /**
   * 某个数据域在**导入**方向上的范围字段。
   *
   * <p>⚠️ 导入方向的范围字段与导出方向**不是一回事**：导出筛的是「导哪一段数据」，
   * 导入筛的是「按哪种模式 / 落到哪个目标对象」——
   * 混成一份会让「导出筛选」出现在导入表单里，而它对导入毫无意义。
   */
  public static List<ScopeField> importScope(String code) {
    if (code == null) return List.of();
    return switch (code.trim().toUpperCase(Locale.ROOT)) {
      case "PRODUCT" ->
          List.of(
              scope("target_entity", "目标对象", "STRING", "SPU / SKU / ATTRIBUTE / PACKAGE / BARCODE"),
              scope("import_mode", "导入模式", "STRING", "CREATE / UPDATE / MATCH_BY_CODE"));
      default -> List.of();
    };
  }

  /** 按编码取数据域；找不到返回 {@code null}。 */
  public static Domain find(String code) {
    if (code == null) return null;
    var normalized = code.trim().toUpperCase(Locale.ROOT);
    for (var d : ALL) {
      if (d.code().equals(normalized)) return d;
    }
    return null;
  }

  /** 按编码取数据域；找不到抛 400（由调用方翻成 Bad Request）。 */
  public static Domain require(String code) {
    var d = find(code);
    if (d == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "数据域不存在：" + code + "（可用：" + codes() + "）");
    }
    return d;
  }

  /** 全部数据域编码（错误信息里用）。 */
  public static String codes() {
    var out = new StringBuilder();
    for (var d : ALL) {
      if (out.length() > 0) out.append(" / ");
      out.append(d.code());
    }
    return out.toString();
  }

  /** 数据域编码 → 数据域（服务层按编码分派时用）。 */
  public static Map<String, Domain> byCode() {
    var out = new LinkedHashMap<String, Domain>();
    for (var d : ALL) out.put(d.code(), d);
    return out;
  }
}
