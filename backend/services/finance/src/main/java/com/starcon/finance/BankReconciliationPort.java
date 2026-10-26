package com.starcon.finance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.finance.BankReconciliationModels.FinanceBankReconSessionPage;
import com.starcon.finance.BankReconciliationModels.FinanceBankReconStatementLinePage;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 **12.7.1.2 银行对账**事实的**只读端口**。
 *
 * <p>蓝图 12.3.2.1 写明「上游 = 12.3.1.1、<b>12.7.1.2</b>」，输入句里有「银行流水」。
 * 银行流水的**写入所有权**是 12.7.1.2（它的九张 {@code finance_bank_recon_*} 表）。
 *
 * <p>🔴 <b>本模块走授权只读 API，不碰它的表。</b>
 * AGENTS.md 的模块边界纪律禁止跨模块 SQL / FK / 视图；12.7.1.2 的表是它的写入所有权，
 * 本模块对它们**零 SELECT、零 INSERT、零 UPDATE、零 DELETE、零 FK、零触发器** ——
 * 连「存在性校验」都不做（连校验都是跨模块耦合）。
 * 数据只从 {@code GET /finance/bank-reconciliation/**} 拿。
 *
 * <p><b>租户不由本端口传递</b>：转发的是**调用方自己的** {@code Authorization} bearer，
 * 12.7.1.2 侧仍按已认证成员关系解析租户并强制 RLS ⇒ 租户隔离是**两道**
 * （本模块 RLS + 12.7.1.2 RLS），而不是「本模块替对方决定租户」。
 *
 * <p>⚠️ <b>为什么是「自己 HTTP 调自己」。</b>两个模块在**同一个服务**里，但它们是
 * **两个模块**：各有各的表、各有各的写入所有权。模块边界靠「谁写谁的表」划线，
 * 不靠「是不是同一个 JVM」。走 HTTP 端口 ⇒ 边界是真的，不是靠约定。
 *
 * <p>⚠️ <b>取不到就显式失败</b>，绝不返回空集合冒充「上游没有流水」——
 * 那会让现场以为这个租户一家银行流水都没有（真实原因可能是权限没给）。
 */
@Service
public class BankReconciliationPort {

  private static final Logger LOG = LoggerFactory.getLogger(BankReconciliationPort.class);

  /** 12.7.1.2 的对账批次列表（授权只读）。 */
  static final String SESSIONS_PATH = "/finance/bank-reconciliation/sessions";

  /** 12.7.1.2 的流水行列表（授权只读）。 */
  static final String LINES_PATH = "/finance/bank-reconciliation/sessions/%s/statement-lines";

  /** 一页最多取多少条（上游自己的上限是 200）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页 —— 防止一个周期里成千上万行时把上游打爆。 */
  static final int MAX_PAGES = 50;

  /**
   * 本模块用到的**一行银行流水**（已归一）。
   *
   * <p>⚠️ {@code occurredOn} 一律是 **UTC 日历日**：PG 的 {@code timestamptz} 不保留原始偏移，
   * 按会话时区归日会让同一条流水在不同部署里算出两个日期 ⇒ 匹配结果不可复现。
   * 统一按 UTC 归日（平台账单侧本来就是日历日 {@code date}，直接用）。
   */
  public record BankStatementLine(
      UUID id,
      UUID sessionId,
      LocalDate occurredOn,
      String direction,
      BigDecimal amount,
      String currency,
      String bankReference,
      String externalReference,
      String counterparty,
      String memo) {}

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final ApplicationContext context;
  private final String configuredUrl;
  private final RestClient client;

  public BankReconciliationPort(
      ApplicationContext context, @Value("${FINANCE_URL:http://127.0.0.1:8087}") String url) {
    this.context = context;
    this.configuredUrl = url;
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 基址。
   *
   * <p>⚠️ <b>运行时解析</b>而不是构造期固定：集成测试用随机端口起真实服务，
   * 端口只有 web server 起来之后才知道 ⇒ 构造期拿到的是配置里的默认端口（连不上）。
   * 这里在**每次调用时**问一次 web server 的真实端口；拿不到（非 web 环境）
   * 才退回配置值。
   */
  private String baseUrl() {
    if (context instanceof WebServerApplicationContext web) {
      var server = web.getWebServer();
      if (server != null) {
        return "http://127.0.0.1:" + server.getPort();
      }
    }
    return configuredUrl;
  }
  /**
   * 取某个账户在给定周期内的**银行/支付平台流水行**。
   *
   * @param bearer 调用方自己的会话（租户由 12.7.1.2 侧按已认证成员关系解析）
   * @param accountId 资金账户（对账范围里的「账户」）
   * @param from 周期开始日（含）
   * @param to 周期结束日（含）
   */
  public List<BankStatementLine> statementLines(
      String bearer, UUID accountId, LocalDate from, LocalDate to) {
    var sessions = sessions(bearer, accountId);
    var result = new ArrayList<BankStatementLine>();
    for (var session : sessions.items()) {
      // 只取**与任务周期有交集**的批次 —— 批次自己的周期是银行的周期，
      // 与任务周期不一致的批次不属于这次对账的范围。
      if (session.periodEnd().isBefore(from) || session.periodStart().isAfter(to)) {
        continue;
      }
      result.addAll(lines(bearer, session.id()));
    }
    return List.copyOf(result);
  }

  /** 12.7.1.2 的对账批次（按账户筛选，真分页拉完）。 */
  private FinanceBankReconSessionPage sessions(String bearer, UUID accountId) {
    var items = new ArrayList<BankReconciliationModels.FinanceBankReconSessionRow>();
    for (int page = 0; page < MAX_PAGES; page++) {
      final int pageNo = page;
      FinanceBankReconSessionPage response;
      try {
        response =
            client
                .get()
                .uri(
                    baseUrl() + SESSIONS_PATH + "?accountId={accountId}&page={page}&size={size}",
                    accountId,
                    pageNo,
                    PAGE_SIZE)
                .header("Authorization", bearer)
                .retrieve()
                .body(FinanceBankReconSessionPage.class);
      } catch (RestClientResponseException e) {
        throw unavailable("12.7.1.2 返回 " + e.getStatusCode().value() + "：银行对账批次读不到");
      } catch (org.springframework.web.client.ResourceAccessException e) {
        throw unavailable("12.7.1.2 不可达");
      }
      if (response == null || response.items() == null) {
        throw unavailable("12.7.1.2 没有返回对账批次");
      }
      items.addAll(response.items());
      if (response.items().size() < PAGE_SIZE
          || response.total() <= (long) (page + 1) * PAGE_SIZE) {
        break;
      }
    }
    return new FinanceBankReconSessionPage(items, 0, items.size(), items.size());
  }

  /** 12.7.1.2 的流水行（真分页拉完）。 */
  private List<BankStatementLine> lines(String bearer, UUID sessionId) {
    var items = new ArrayList<BankStatementLine>();
    for (int page = 0; page < MAX_PAGES; page++) {
      final int pageNo = page;
      FinanceBankReconStatementLinePage response;
      try {
        response =
            client
                .get()
                .uri(
                    baseUrl() + String.format(LINES_PATH, sessionId) + "?page={page}&size={size}",
                    pageNo,
                    PAGE_SIZE)
                .header("Authorization", bearer)
                .retrieve()
                .body(FinanceBankReconStatementLinePage.class);
      } catch (RestClientResponseException e) {
        throw unavailable("12.7.1.2 返回 " + e.getStatusCode().value() + "：银行流水读不到");
      } catch (org.springframework.web.client.ResourceAccessException e) {
        throw unavailable("12.7.1.2 不可达");
      }
      if (response == null || response.items() == null) {
        throw unavailable("12.7.1.2 没有返回银行流水");
      }
      for (var row : response.items()) {
        items.add(
            new BankStatementLine(
                row.id(),
                row.sessionId(),
                row.occurredAt() == null
                    ? null
                    : row.occurredAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                row.direction(),
                row.amount(),
                row.currency(),
                row.bankReference(),
                row.externalReference(),
                row.counterparty(),
                row.memo()));
      }
      if (response.items().size() < PAGE_SIZE
          || response.total() <= (long) (page + 1) * PAGE_SIZE) {
        break;
      }
    }
    return items;
  }

  /** 上游不可用 / 读不到一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    LOG.warn("12.7.1.2 银行流水读不到：{}", detail);
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
