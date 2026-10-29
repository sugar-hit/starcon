package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13.1.2.1 目标设定的**组织对象只读端口**（归属对象的权威来源）。
 *
 * <p>🔴 **蓝图关系句的命门**：「目标要挂在组织对象上（部门/人/店铺），
 * 否则无法与绩效核算对接（行业普遍需求）。」所以本端口是本模块的**必需依赖**，不是可选装饰。
 *
 * <p>⚠️ **组织本体不在本服务**：
 *
 * <ul>
 *   <li><b>部门 / 人员</b> ⇒ identity 服务（{@code department} / {@code membership}）；
 *   <li><b>店铺</b> ⇒ channel 服务，本服务已有它的投影档案（{@code reporting_store_profile}），
 *       直接读本库即可，**不走端口**；
 *   <li><b>产品</b> ⇒ catalog 服务（SPU）。</li>
 * </ul>
 *
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经上游的授权 HTTP API</b>读，不直连对方库。
 *
 * <p>⚠️ <b>不传租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**。
 *
 * <p>⚠️ **上游读不到就是读不到**：本端口把上游故障原样翻成 503（消息写明「读不到」
 * 而不是「没有数据」），绝不返回空集合冒充「上游没有数据」。
 *
 * <p>⚠️ **产品维度为什么也做**：蓝图 13.1.2.1 的**职责句**原文是
 * 「按部门/人员/<b>产品</b>/店铺设定销售额、毛利额、毛利率、广告 ROI、库存周转等指标与周期」——
 * 产品是蓝图点名的第四个归属维度（关系句只强调了「挂在组织对象上」的那三类）。
 * 所以本模块**做**产品维度；它的事实来源是 catalog 的 SPU 清单。
 */
@Service
public class ReportingTargetOrgPort {

  private final ReportingUpstreamClient identity;
  private final ReportingUpstreamClient catalog;

  public ReportingTargetOrgPort(
      @Value("${IDENTITY_URL}") String identityUrl,
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl) {
    this.identity = ReportingUpstreamClient.to("identity 服务", identityUrl);
    this.catalog = ReportingUpstreamClient.to("catalog 服务", catalogUrl);
  }

  // ==================================================================
  // identity：部门
  // ==================================================================

  /** 上游的部门节点（只声明本端口真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DepartmentNode(
      UUID id,
      String code,
      String name,
      UUID parentId,
      UUID leaderMembershipId,
      String leaderName,
      int memberCount,
      List<DepartmentNode> children) {}

  /**
   * 拉部门清单（**扁平化**成一张表）。
   *
   * <p>⚠️ 上游给的是**树**（每个节点带 children）。本端口把树摊平 ——
   * 目标只需要「有哪些部门」，层级信息留在 {@code parentId} 里。
   */
  public List<DepartmentNode> departments(String bearer) {
    var roots =
        identity.list(
            uriBuilder -> uriBuilder.path("/identity/departments"),
            bearer,
            new org.springframework.core.ParameterizedTypeReference<
                List<DepartmentNode>>() {});
    return flatten(roots);
  }

  private List<DepartmentNode> flatten(List<DepartmentNode> nodes) {
    var out = new ArrayList<DepartmentNode>();
    for (var node : nodes) {
      if (node == null) continue;
      out.add(node);
      if (node.children() != null && !node.children().isEmpty()) {
        out.addAll(flatten(node.children()));
      }
    }
    return out;
  }

  // ==================================================================
  // identity：人员
  // ==================================================================

  /** 上游的成员行（只声明本端口真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MemberRow(
      UUID id, UUID userId, String username, String status, UUID departmentId, boolean owner) {}

  /** 拉人员清单。 */
  public List<MemberRow> members(String bearer) {
    return identity.list(
        uriBuilder -> uriBuilder.path("/identity/members"),
        bearer,
        new org.springframework.core.ParameterizedTypeReference<List<MemberRow>>() {});
  }

  // ==================================================================
  // catalog：产品（SPU）
  // ==================================================================

  /** 上游的产品行（只声明本端口真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProductRow(
      UUID id, String code, String name, String brand, String category, String status, int skuCount) {}

  /** 拉产品清单。 */
  public List<ProductRow> products(String bearer) {
    return catalog.list(
        uriBuilder -> uriBuilder.path("/catalog/products"),
        bearer,
        new org.springframework.core.ParameterizedTypeReference<List<ProductRow>>() {});
  }
}
