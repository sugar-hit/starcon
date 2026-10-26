package com.starcon.identity;

import static com.starcon.identity.AccountLocaleModels.AccountLocaleCapabilities;
import static com.starcon.identity.AccountLocaleModels.AccountLocaleInput;
import static com.starcon.identity.AccountLocaleModels.AccountLocaleView;

import com.starcon.tenancy.TenantTransactions;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.4.2.1 语言与时区 —— 端点（identity 服务）。
 *
 * <p>蓝图：「界面语言（中文/英文等）与展示时区，影响时间显示与日期分组，**不改变存储时区**。
 * 输入：用户选择；平台站点时区清单。输出：界面语言包与时间显示口径。」
 *
 * <p><b>为什么是独立端点而不是复用 {@code /identity/user-preferences/{pageKey}}</b>：
 *
 * <ol>
 *   <li><b>需要能力声明</b>：本模块有一半能力（界面语言）当前不可用，
 *       得有一处如实报出来（{@code /capabilities}），通用偏好端点没有这个形状。</li>
 *   <li><b>需要语义校验</b>：通用偏好端点只守「结构安全」（键名/深度/体积/敏感字段），
 *       不守「这个时区是不是真的存在」。本端点在写入前补上这一层。</li>
 *   <li><b>需要防御性回落</b>：绕过本端点用通用端点写了畸形时区时，读回来必须回落并说清原因。</li>
 * </ol>
 *
 * <p><b>存储仍然复用同一张 {@code user_preference} 表</b>（页面 key = {@code account.locale}）：
 * 本模块不建表、不 ALTER 任何表。
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾
 * 没有出现过，伪造它改不动 {@code app.current_tenant}；请求体里也不接受
 * tenantId / membershipId / userId。
 */
@RestController
@RequestMapping("/identity/user-locale")
public class AccountLocaleController {

  private final IdentityController identity;
  private final AccountLocaleService locales;
  private final TenantTransactions tenants;

  public AccountLocaleController(
      IdentityController identity,
      AccountLocaleService locales,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.identity = identity;
    this.locales = locales;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 统一入口：先确认会话（租户与 membership_id 都从这里来），再在租户事务里执行。 */
  private <T> T call(String auth, Function<IdentityController.Access, T> work) {
    IdentityController.Access actor = identity.resolve(auth);
    UUID membershipId = actor.membershipId();
    if (membershipId == null) {
      throw IdentityController.error(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenants.run(actor.tenantId(), () -> work.apply(actor));
  }

  /** 读我当前生效的界面语言与展示时区。没存过时返回默认值（{@code stored=false}），不是 404。 */
  @GetMapping
  public AccountLocaleView read(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        actor -> locales.read(actor.tenantId(), actor.membershipId()));
  }

  /**
   * 能力声明：逐条报出「能做什么、不能做什么、缺什么、归谁」。
   *
   * <p>界面语言这一维当前是 {@code available=false} —— 本仓没有 i18n 运行时与语言包。
   */
  @GetMapping("/capabilities")
  public AccountLocaleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> locales.capabilities());
  }

  /**
   * 保存我选的界面语言与展示时区。
   *
   * <p>时区不是合法 IANA 时区、或语言当前没有语言包 ⇒ 400 并说清原因（不静默改成默认）。
   */
  @PutMapping
  public AccountLocaleView save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AccountLocaleInput input) {
    return call(
        auth,
        actor -> locales.save(actor.tenantId(), actor.membershipId(), input));
  }

  /** 重置（删掉那一行 = 回到默认）。幂等：本来没存过也算成功。 */
  @DeleteMapping
  public void reset(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    call(
        auth,
        actor -> {
          locales.reset(actor.tenantId(), actor.membershipId());
          return null;
        });
  }

  /**
   * 本模块的 400：**带一句能直接显示在界面上的原因**。
   *
   * <p>刻意用<b>控制器局部</b>的处理器，而不是往全局 {@code ApiErrorHandler} 里加分支 ——
   * 全局那处一改，identity 其它模块的错误响应形状就跟着变了。
   *
   * <p>响应体是标准 ProblemDetail（{@code detail} 字段），前端 {@code api()} 读的就是它。
   */
  @ExceptionHandler(AccountLocaleException.class)
  ResponseEntity<ProblemDetail> invalid(AccountLocaleException e) {
    return ResponseEntity.badRequest()
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
  }
}
