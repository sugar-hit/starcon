package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * 1.2.1.1 全局搜索框 —— catalog 侧的只读检索响应形状。
 *
 * <p><b>各服务各有一份同名形状</b>（catalog / trade / channel / warehouse / identity），
 * 而不是抽一个共享 DTO 包：共享包会把五个服务的响应耦合成一个必须同时演进的类型，
 * 而这五个端点的演进节奏完全不同（哪个域先接上、哪个域先下线都不影响其它四个）。
 * 字段名与语义逐字一致，前端合并时按 {@code domain} 归组即可。
 *
 * <p><b>本模块不产生新的业务事实</b>：这里的一切都是 catalog 已有的商品与往来单位主数据的**只读投影**，
 * 写入所有权仍在 3.1.x / 3.6.x 那些模块手里。
 */
public final class GlobalSearchModels {

  private GlobalSearchModels() {}

  /**
   * 一条命中。
   *
   * @param domain 业务域（PRODUCT / CUSTOMER / SUPPLIER）；前端按它归组
   * @param kind 域内具体实体（SKU / SPU / PARTY_CUSTOMER / PARTY_SUPPLIER）
   * @param id 业务主键
   * @param primary 主标识（SKU 编码 / 单号 / 往来单位编码）
   * @param secondary 副信息（名称 / 状态 / 归属），可空
   */
  public record GlobalSearchHit(
      String domain,
      String kind,
      UUID id,
      String primary,
      @Schema(nullable = true) String secondary) {}

  /** 一个业务域的命中集合。空组不返回（空组会让界面出现「有这一类但一条都没有」的噪声）。 */
  public record GlobalSearchGroup(
      String domain,
      String label,
      List<GlobalSearchHit> hits,
      String dataScope,
      String dataScopeNote) {}

  /**
   * 因**权限**未返回的一个子域。
   *
   * <p>域码与 {@code unavailableDomains} 里的一致（catalog 侧是 {@code PRODUCT_SKU} /
   * {@code PRODUCT_SPU} 这种带域内实体前缀的码），额外带上它归属的业务域 ——
   * 前端要按**业务域**计数（「N 个业务域因权限未返回」）并把提示挂到对应的组上，
   * 光有一串子域码是算不出业务域数的。
   *
   * @param domain 被抑制的子域码
   * @param label 该子域的中文名（SKU / SPU / 订单 ...）
   * @param group 归属的业务域码（{@code PRODUCT} / {@code ORDER} ...）
   * @param groupLabel 归属业务域的中文名（商品 / 订单 ...）
   */
  public record GlobalSearchSuppressed(
      String domain, String label, String group, String groupLabel) {}

  /**
   * 检索响应。
   *
   * @param keyword 归一化后的关键词（去首尾空白）
   * @param matchMode 匹配方式：PREFIX_OR_EXACT（前缀 / 精确，不做全文语义检索）
   * @param minKeywordLength 最小关键词长度（少于这个长度后端直接 400）
   * @param groups 命中的业务域
   * @param unavailableDomains 因**权限**未返回的域（不是「没有数据」，是「没权限看」）
   * @param suppressedDomains 因权限未返回的子域（带归属业务域；被抑制的**命中条数**不返回）
   */
  public record GlobalSearchResponse(
      String keyword,
      String matchMode,
      int minKeywordLength,
      List<GlobalSearchGroup> groups,
      List<String> unavailableDomains,
      List<GlobalSearchSuppressed> suppressedDomains) {}

  /**
   * 一个业务域的能力声明。
   *
   * @param domain 域码
   * @param label 中文名
   * @param available 该域在本服务是否真的接通
   * @param ownerModule 该域的归属模块（available=false 时写明「谁交付之后它才会变成真数据」）
   * @param source 真实依据（表 + 列 + 匹配方式），available=true 时必填
   * @param matchMode 匹配方式
   * @param impact 影响说明：拿不到会怎样 / 为什么不能拿假的顶替
   */
  public record GlobalSearchDomain(
      String domain,
      String label,
      boolean available,
      String ownerModule,
      @Schema(nullable = true) String source,
      String matchMode,
      String impact) {}

  /** 能力声明整体。 */
  public record GlobalSearchCapabilities(List<GlobalSearchDomain> domains, List<String> notes) {}
}
