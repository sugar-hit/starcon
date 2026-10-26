package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 16.3.1.2 申报日历读取 <b>identity 成员目录</b>的<b>跨服务只读端口</b>。
 *
 * <p>「责任人」必须落到<b>人</b>身上：租户里的成员（{@code membership.id}）才是
 * identity（17 组织权限）拥有的那个实体。申报日历在 channel 库里，因此这里只存
 * <b>逻辑 ID</b>，并<b>不建跨库外键、也不复制成员姓名</b>（与 16.3.1.1 不复制法人名称
 * 同一纪律）。
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口
 * <b>只经 identity 的授权 HTTP API</b>读：{@code GET /identity/members}。
 *
 * <p>🔴 <b>读不到就是读不到</b>：本端口<b>不抛异常</b>，返回 {@code available=false} + 原因。
 * 上层据此**拒绝落库**一个查无此人的责任人（落库前校验），
 * 而不是存一个 id 然后在页面上显示成空白。
 *
 * <p>🔴 <b>成员姓名不复制到本库</b>：显示时经本端口现读；读不到就显示「读不到」，
 * <b>不显示成「没有责任人」</b> —— 这两者的处置动作完全不同。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * identity 侧仍按已认证成员关系解析租户。
 */
@Service
public class MemberDirectorySourcePort {

  /** 上游只读形状：{@code GET /identity/members}（租户成员目录）。 */
  static final String MEMBERS_PATH = "/identity/members";

  /**
   * 一个成员的最小引用（只读、只用于显示与校验）。
   *
   * @param membershipId 成员 ID
   * @param username 成员名
   * @param status 成员状态
   */
  public record MemberRef(UUID membershipId, String username, String status) {}

  /**
   * 一次读取的结果。
   *
   * @param available 上游读到了吗（false ⇒ 显示「读不到」，**不是**「没有成员」）
   * @param items 成员
   * @param note 读不到 / 读到了什么的原因说明
   */
  public record MemberSet(boolean available, List<MemberRef> items, String note) {}

  /** 上游返回体（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record MemberRow(UUID id, String username, String status) {}

  private final RestClient identity;

  public MemberDirectorySourcePort(@Value("${IDENTITY_URL}") String identityUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.identity = RestClient.builder().baseUrl(identityUrl).requestFactory(factory).build();
  }

  /**
   * 读租户成员目录。
   *
   * @param bearer 调用方自己的 bearer（转发它，租户由 identity 按成员关系解析）
   */
  public MemberSet members(String bearer) {
    try {
      List<MemberRow> rows =
          identity
              .get()
              .uri(MEMBERS_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<MemberRow>>() {});
      if (rows == null) {
        return new MemberSet(false, List.of(), "identity 服务没有返回成员目录 —— **读不到**。");
      }
      List<MemberRef> items = new ArrayList<>();
      for (MemberRow row : rows) {
        if (row == null || row.id() == null) {
          continue;
        }
        items.add(
            new MemberRef(
                row.id(),
                row.username() == null ? "" : row.username(),
                row.status() == null ? "" : row.status()));
      }
      return new MemberSet(true, items, "已从 identity 读到本租户成员目录。");
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 401 || status == 403) {
        return new MemberSet(
            false, List.of(), "调用方无权读取成员目录（identity 拒绝 " + status + "）—— **读不到**。");
      }
      return new MemberSet(false, List.of(), "identity 服务返回 " + status + " —— 成员目录**读不到**。");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new MemberSet(false, List.of(), "identity 服务不可达 —— 成员目录**读不到**。");
    } catch (RuntimeException e) {
      return new MemberSet(false, List.of(), "读取成员目录失败：" + e.getMessage() + " —— **读不到**。");
    }
  }
}
