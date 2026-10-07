package com.starcon.catalog;

import static com.starcon.catalog.ZombieCandidateModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.9.2.2 僵尸主数据清理：候选清单、规则配置、批量停用、停用记录，以及上游引用快照接入。
 *
 * <p>路由分两类，这是**服务边界**而不是命名偏好：
 *
 * <ul>
 *   <li>{@code /catalog/zombie-candidates/*} —— 运营用的读与治理面。
 *       租户来自已认证成员关系（{@link AccessService} 向 identity 解析），
 *       <b>不读 {@code X-Tenant-Id} 头</b>：那是客户端自称的租户，AGENTS.md 明令禁止。
 *   <li>{@code /catalog/zombie-candidates/reference-stats} —— 上游服务推送引用观测的入口。
 *       走同一套权限与租户解析（推送方必须携带**本租户成员**的凭据，
 *       不是拿一个全局服务账号去写所有租户的数据），所以它挂在同一个控制器里而不是另开一个。
 * </ul>
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，
 * 与 3.6.x 完全一致。不新造权限词表 —— 那要改 identity 服务的迁移，
 * 属于另一个服务的写入所有权。
 */
@RestController
@RequestMapping("/catalog/zombie-candidates")
public class ZombieCandidateController {
  private final AccessService access;
  private final ZombieCandidateService service;

  public ZombieCandidateController(AccessService access, ZombieCandidateService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 词表与概览

  /**
   * 词表：主体类型、引用维度、判定状态、停用目标。
   *
   * <p>维度的 {@code wired} 字段告诉前端「哪些上游事件当前真的存在」，
   * 界面据此禁用未接入的维度开关 —— 与 warehouse 的 {@code /inventory/capabilities}
   * 同一处理方式。
   */
  @GetMapping("/options")
  public ZombieOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.options());
  }

  /** 概览：三种主体各自的活跃数与三态分布 + 规则参数 + 未接入的上游维度清单。 */
  @GetMapping("/overview")
  public ZombieCandidateOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  // ------------------------------------------------------------ 规则配置

  /** 三种主体的规则（没配过的返回默认值并带 {@code configured=false}）。 */
  @GetMapping("/rules")
  public List<ZombieCleanupRuleView> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.rules());
  }

  /** 保存某个主体类型的清理规则（首次保存即 INSERT，之后走乐观锁）。 */
  @PutMapping("/rules/{entityType}")
  public ZombieCleanupRuleView saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String entityType,
      @Valid @RequestBody ZombieCleanupRuleInput input) {
    return call(
        auth, "spu.update", a -> service.saveRule(a, type(entityType), input));
  }

  // ------------------------------------------------------------ 候选清单

  /**
   * 僵尸候选清单：分页 + 主体类型 / 关键词 / 判定状态筛选。
   *
   * <p>每行带 {@code verdict} 与逐维 {@code dimensions} —— 界面要能显示"凭什么算僵尸"，
   * 而"无法判定"（引用维度缺观测）**必须**与"在用"分开显示：前者的下一步动作是
   * 去接上游事件或刷新快照，后者是"这条有人在用，别动"。
   */
  @GetMapping
  public ZombieCandidatePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String verdict,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth, "spu.read", a -> service.list(new ZombieCandidateQuery(entityType, keyword, verdict, page, size)));
  }

  /** 单条主数据的引用证据 + 最近停用记录（详情面板）。 */
  @GetMapping("/evidence")
  public ZombieReferenceEvidence evidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String entityType,
      @RequestParam UUID entityId) {
    return call(auth, "spu.read", a -> service.evidence(type(entityType), entityId));
  }

  // ------------------------------------------------------------ 上游快照接入

  /**
   * 上游服务推送引用观测（授权 API）。
   *
   * <p>四个维度里当前**只有库存**（warehouse / 07）已接入；订单（trade）与成本（purchase）
   * 的服务在本仓库不存在，刊登（channel / 04）尚无刊登实体。推送到未接入的维度
   * 是允许的 —— 上游一旦落地就能立刻推，不需要本模块再改 schema 或规则。
   *
   * <p>响应区分 {@code written} 与 {@code rejected}：主体不存在或角色不匹配的条目会被
   * 触发器拒掉并逐条回报原因，而不是整批静默丢弃 —— 静默丢弃会让上游以为推送成功，
   * 而那一维会永远停留在"未知"。
   */
  @PostMapping("/reference-stats")
  public ZombieReferenceStatPushResult pushStats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String entityType,
      @Valid @RequestBody ZombieReferenceStatPush push) {
    return call(
        auth, "spu.update", a -> service.pushStats(a, type(entityType), push));
  }

  // ------------------------------------------------------------ 批量停用

  /**
   * 批量停用僵尸主数据（**停用而非删除**）。
   *
   * <p>准入判定在服务端**重算**而不是信任前端传来的判定：前端可能拿着上一份清单，
   * 而上游在这中间推了新快照。
   *
   * <p>整批原子，准入失败返回 409 且带 {@code blocked} 清单 —— 不跳过、不部分成功。
   */
  @PostMapping("/deactivate")
  public ZombieDeactivateResult deactivate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String entityType,
      @Valid @RequestBody ZombieDeactivateInput input) {
    return call(
        auth, "spu.update", a -> service.deactivate(a, type(entityType), input));
  }

  // ------------------------------------------------------------ 停用记录

  /** 停用台账：时间倒序 + 主体类型 / 主体 / 操作人筛选（蓝图「输出」里的"停用记录"）。 */
  @GetMapping("/records")
  public ZombieCleanupRecordPage records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) UUID entityId,
      @RequestParam(required = false) UUID decidedBy,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth, "spu.read", a -> service.records(new ZombieRecordQuery(entityType, entityId, decidedBy, page, size)));
  }

  // ------------------------------------------------------------ 停用目标（供下游核对）

  /**
   * 停用目标状态词表。刻意做成独立端点而不是散落在文档里：下游（3.1.1.1 列表）
   * 要知道自己该把哪些状态当成"不可选"，而这个口径只有一处。
   */
  @GetMapping("/deactivation-targets")
  public Map<String, Object> deactivationTargets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth, "spu.read",
        a ->
            Map.of(
                "status", "INACTIVE",
                "note", "僵尸主数据只停用不删除：主数据被引用后只能停用，历史单据仍需展示"));
  }

  private static ZombieEntityType type(String value) {
    try {
      return ZombieEntityType.parse(value);
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }
}