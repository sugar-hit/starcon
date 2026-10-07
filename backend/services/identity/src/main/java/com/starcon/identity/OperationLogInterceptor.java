package com.starcon.identity;

import com.starcon.tenancy.TenantTransactions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 17.5.3.2 API 调用摘要与错误堆栈的采集点。
 *
 * <p>蓝图要求记录「API 调用摘要、任务执行、错误堆栈」。后两者由具体写入点记录，
 * 而 API 调用摘要是<b>横切</b>关注点：每个请求都发生一次，不是某个业务方法里的语句。
 * 采集点因此放在拦截器上——往业务代码里到处插记录语句，既会漏，也会把业务代码搅乱。
 *
 * <p>记录时机是请求<b>结束后</b>：那时才拿得到最终状态码与异常，也才量得出完整耗时。
 *
 * <p>写日志失败<b>绝不影响业务响应</b>：排障日志反过来变成故障源比缺一条日志糟糕得多。
 * 所以这里统一 catch 并吞掉。
 */
@Component
@Order(0)
public class OperationLogInterceptor implements HandlerInterceptor {
  private final OperationLog log;
  private final IdentityController identity;
  private final TenantTransactions tenants;

  public OperationLogInterceptor(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.log = new OperationLog(sql);
    this.identity = identity;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  private static final String START = OperationLogInterceptor.class.getName() + ".start";

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    RequestTrace.begin(request);
    request.setAttribute(START, System.nanoTime());
    // 把 request_id 回写到响应头：排障时前端/网关能把 ID 递回来，截图里就带着线索。
    var requestId = RequestTrace.requestId();
    if (requestId != null) response.setHeader(RequestTrace.HEADER, requestId);
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
    var started = request.getAttribute(START);
    var duration =
        started instanceof Long begin
            // 纳秒 → 毫秒，保留 3 位小数：排障要看亚毫秒抖动，整数毫秒会把卡顿压成 0。
            ? BigDecimal.valueOf(System.nanoTime() - (long) begin)
                .divide(BigDecimal.valueOf(1_000_000), 3, RoundingMode.HALF_UP)
            : null;
    var requestId = RequestTrace.requestId();
    var traceId = RequestTrace.traceId();
    // 未认证的请求（登录接口自身等）拿不到租户上下文，因此不写：
    // tenant_id NOT NULL 是多租户硬约束，宁可不记也不硬塞一个租户进去。
    IdentityController.Access access;
    try {
      access = identity.resolve(request.getHeader("Authorization"));
    } catch (Exception e) {
      return;
    }
    var status = response.getStatus();
    // 路径只取 URI，不带查询串：查询串里常有令牌与业务标识。
    var path = request.getRequestURI();
    try {
      tenants.run(
          access.tenantId(),
          () -> {
            var name = actorName(request);
            log.apiCall(
                access.tenantId(), requestId, traceId, access.userId(), name,
                request.getMethod(), path, status, duration, status >= 400);
            if (error != null)
              log.error(
                  access.tenantId(), requestId, traceId, access.userId(), name,
                  request.getMethod() + " " + path + " 处理失败", error, Map.of("status", status));
            return null;
          });
    } catch (Exception ignored) {
      // 见类注释：排障日志不能反过来把业务请求搞挂。
    }
  }

  /** 操作人可读名。查不到就返回 null——宁可缺一个名字，也不写一个错的。 */
  private String actorName(HttpServletRequest request) {
    try {
      var row =
          log.sql()
              .fetchOne(
                  "select session_actor_name(?) as name",
                  IdentityController.hash(identity.token(request.getHeader("Authorization"))));
      return row == null ? null : row.get("name", String.class);
    } catch (Exception e) {
      return null;
    }
  }

  /** 注册到 MVC 管线。只拦截本服务的 API，不碰静态资源与文档端点。 */
  @Component
  static class Registration implements WebMvcConfigurer {
    private final OperationLogInterceptor interceptor;

    Registration(OperationLogInterceptor interceptor) {
      this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
      registry
          .addInterceptor(interceptor)
          .addPathPatterns("/identity/**", "/auth/**")
          // 契约导出端点：每次跑测试都写一条日志只会污染操作日志表，没有排障价值。
          .excludePathPatterns("/v3/api-docs/**");
    }
  }
}