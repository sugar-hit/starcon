package com.starcon.platform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 18.1.3.2「数据迁移」的模型层。
 *
 * <p>蓝图原文：「<b>职责</b>：支持从其他 ERP 迁移历史数据：映射配置（外部字段 → 内部模型）、
 * 试运行预览、正式迁移、失败回滚与核对报告。<b>输入</b>：源数据文件；字段映射配置。
 * <b>输出</b>：迁移任务 + 迁移核对报告（成功/失败/跳过数量与原因）。
 * <b>关系</b>：上游 = 18.1.3.1；下游 = 各业务域。
 * <b>历史数据迁移必须支持试运行</b>，直接正式跑会产生大量脏数据难以回退。」
 *
 * <p><b>六条纪律：</p>
 *
 * <ol>
 *   <li><b>五项能力各有各的落点</b>：映射配置（{@code platform_migration_mapping}）、
 *       试运行 / 正式迁移（{@code platform_migration_run.mode}）、
 *       失败回滚（{@code rollback_status} + 逐行的 {@code upstream_id}）、
 *       核对报告（{@code platform_migration_file.role='REPORT'}）。
 *   <li><b>「试运行」不是「加个开关」</b>：试运行**一个写端点都不调**，
 *       数据层还有一道触发器兜底（试运行的行不许带 {@code upstream_id}）。
 *   <li><b>回滚是真的回滚</b>：回滚按逐行记下的 {@code upstream_id} 去目标库**真删**。
 *       没有 create/delete 对的域不做迁移 —— 否则「已回滚」会是假的。
 *   <li><b>核对报告的三个口径加起来等于处理过的行数</b>（数据库 CHECK 钉死）：
 *       成功 / 失败 / 跳过加不到总数上，那份报告在账上就对不平。
 *   <li><b>跨服务只走授权端口</b>：本模块**一条跨库 SQL 都没有**；
 *       目标库的数据只经它自己的授权 API 读写，<b>只转发调用方自己的 bearer</b>。
 *   <li><b>时间一律 timestamptz</b>：服务层用 {@link OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}（用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会静默偏 −28800s）。
 * </ol>
 */
public final class DataMigrationModels {

  private DataMigrationModels() {}

  // ================================================================ 词表

  /** 迁移模式：试运行 / 正式迁移。 */
  public enum Mode {
    /** 试运行：只预演，**一个写端点都不调**。 */
    DRY_RUN("试运行"),
    /** 正式迁移：真的写目标库。 */
    APPLY("正式迁移");

    private final String label;

    Mode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析（大小写不敏感）；不认识的值返回 {@code null}（由调用方翻成 400）。 */
    public static Mode parse(String raw) {
      if (raw == null) return null;
      for (var m : values()) {
        if (m.name().equalsIgnoreCase(raw.trim())) return m;
      }
      return null;
    }
  }

  /** 迁移状态。 */
  public enum RunStatus {
    PENDING("待执行"),
    RUNNING("执行中"),
    SUCCEEDED("已完成"),
    FAILED("已失败"),
    CANCELLED("已作废");

    private final String label;

    RunStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 回滚状态（**独立于迁移状态机**）。 */
  public enum RollbackStatus {
    /** 没回滚过（默认）。 */
    NONE("未回滚"),
    RUNNING("回滚中"),
    SUCCEEDED("已回滚"),
    FAILED("回滚失败");

    private final String label;

    RollbackStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 逐行结论。 */
  public enum RowOutcome {
    /** 迁成了（APPLY 下真的写进了目标库）。 */
    SUCCEEDED("成功"),
    /** 迁不成。 */
    FAILED("失败"),
    /** 有意不迁（重复 / 已存在 / 查不清）。 */
    SKIPPED("跳过");

    private final String label;

    RowOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 逐行的回滚结论（与迁移结论**分开**）。 */
  public enum RowRollbackStatus {
    NONE("未回滚"),
    DELETED("已删除"),
    FAILED("未删除");

    private final String label;

    RowRollbackStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 值的转换方式。 */
  public enum Transform {
    /** 原样。 */
    NONE("原样"),
    /** 去首尾空白。 */
    TRIM("去首尾空白"),
    /** 转大写。 */
    UPPER("转大写"),
    /** 转小写。 */
    LOWER("转小写");

    private final String label;

    Transform(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析（大小写不敏感）；不认识的值返回 {@code null}（由调用方翻成 400）。 */
    public static Transform parse(String raw) {
      if (raw == null) return NONE;
      var text = raw.trim();
      if (text.isEmpty()) return NONE;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(text)) return t;
      }
      return null;
    }
  }

  // ================================================================ 映射配置

  /**
   * 一条字段映射：**外部列名 → 内部模型字段**。
   *
   * @param source 外部列名（源文件表头，逐字匹配）
   * @param target 内部字段键（必须是目标模型的合法字段）
   * @param required 源文件缺这一列 / 值为空 ⇒ 该行失败
   * @param transform 值转换（NONE / TRIM / UPPER / LOWER）
   * @param defaultValue 源值为空时的兜底值（只对非 required 字段有意义）
   */
  public record DataMigrationField(
      String source,
      String target,
      boolean required,
      String transform,
      String defaultValue) {}

  /** 映射配置行。 */
  public record DataMigrationMappingRow(
      UUID id,
      String code,
      String name,
      String sourceSystem,
      String targetModel,
      List<DataMigrationField> fields,
      boolean enabled,
      String remark,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 映射配置列表（真分页）。 */
  public record DataMigrationMappingPage(
      List<DataMigrationMappingRow> items, int page, int size, long total) {}

  /** 映射配置入参。 */
  public record DataMigrationMappingInput(
      String code,
      String name,
      String sourceSystem,
      String targetModel,
      List<DataMigrationField> fields,
      Boolean enabled,
      String remark) {}

  // ================================================================ 目标模型目录

  /**
   * 一个可迁移的内部模型（程序内稳定标识 + 字段清单 + 边界）。
   *
   * @param code 稳定标识（SPU / SKU）
   * @param label 中文名
   * @param ownerService 属主服务（**迁移要由属主服务写**，见 AGENTS.md 模块边界纪律）
   * @param ownerModule 归属模块（蓝图里的 L3 / L4 编号）
   * @param fields 这个模型的字段清单
   * @param limitation 接通的边界（写明哪些没做、为什么）
   */
  public record DataMigrationTargetRow(
      String code,
      String label,
      String ownerService,
      String ownerModule,
      List<DataMigrationTargets.DataMigrationTargetField> fields,
      String limitation) {}

  // ================================================================ 迁移任务

  /** 迁移任务行 = 「迁什么 / 迁多少 / 迁到哪 / 结论」。 */
  public record DataMigrationRunRow(
      UUID id,
      String runNo,
      String mode,
      String targetModel,
      UUID mappingId,
      String mappingCode,
      String sourceFilename,
      String sourceSha256,
      String status,
      String rollbackStatus,
      int totalRows,
      int processedRows,
      int succeededRows,
      int failedRows,
      int skippedRows,
      int rolledBackRows,
      UUID reportFileId,
      UUID rollbackReportFileId,
      String error,
      UUID actorId,
      OffsetDateTime requestedAt,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      OffsetDateTime rolledBackAt,
      OffsetDateTime createdAt) {}

  /** 迁移任务列表（真分页）。 */
  public record DataMigrationRunPage(
      List<DataMigrationRunRow> items, int page, int size, long total) {}

  // ================================================================ 逐行结论

  /** 一行源数据的迁移结论（**回滚的依据**）。 */
  public record DataMigrationRowRow(
      UUID id,
      UUID runId,
      int rowNo,
      String outcome,
      String targetKind,
      String targetCode,
      UUID upstreamId,
      UUID upstreamParentId,
      String reasonCode,
      String message,
      String rawValue,
      String rollbackStatus,
      String rollbackMessage,
      OffsetDateTime createdAt) {}

  /** 逐行结论分页（附三类计数）。 */
  public record DataMigrationRowPage(
      List<DataMigrationRowRow> items,
      int page,
      int size,
      long total,
      int succeededCount,
      int failedCount,
      int skippedCount) {}

  // ================================================================ 概览与能力声明

  /** 概览：几个数。 */
  public record DataMigrationOverview(
      long totalRuns,
      long dryRunRuns,
      long applyRuns,
      long runningRuns,
      long succeededRuns,
      long failedRuns,
      long skippedRuns,
      long rolledBackRuns,
      long mappingCount,
      long rowCount,
      OffsetDateTime now) {}

  /** 能力声明的一格。 */
  public record DataMigrationCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String limitation) {}

  /** 兄弟模块的接线状态。 */
  public record DataMigrationGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String relation,
      String note) {}

  /** 能力声明整体。 */
  public record DataMigrationCapabilities(
      List<DataMigrationCapability> capabilities, List<DataMigrationGap> gaps) {}

  // ================================================================ 文件与入参

  /** 下载用的一次性载荷。 */
  public record DataMigrationFileContent(
      String filename, String contentType, byte[] content) {}

  /** 发起迁移的入参（**base64 JSON**：浏览器的同源代理只转发 JSON，multipart 过不去）。 */
  public record DataMigrationRunInput(
      UUID mappingId, String mode, String filename, String contentBase64) {}

  /** 文件元信息（不含内容：内容在存储里，按 storage_key 取）。 */
  public record DataMigrationFileRow(
      UUID id,
      UUID runId,
      String role,
      String filename,
      String contentType,
      String contentSha256,
      BigDecimal byteSize,
      int rowCount,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt) {}

  /** 映射快照（冻结在迁移任务里：**审计要回答的是「当时按什么映射迁的」**）。 */
  public record DataMigrationMappingSnapshot(
      String code, String name, String sourceSystem, String targetModel, List<DataMigrationField> fields) {}

  /** 迁移的内部结果（不直接暴露给前端）。 */
  public record DataMigrationOutcome(
      Map<String, String> values,
      String reasonCode,
      String message) {}
}
