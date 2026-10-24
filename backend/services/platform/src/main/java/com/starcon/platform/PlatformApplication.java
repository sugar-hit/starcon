package com.starcon.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 18 集成开放与智能自动化服务（蓝图 18 卷「后端归属」：platform 是开放平台 18.1、
 * 外部系统对接 18.2、业务规则引擎 18.3 与 AI 治理 18.4 的**唯一**所有者）。
 *
 * <p>服务引导与第一条业务模块 18.1.1.1 应用与凭证（迁移 V377）同时落地。卷 18 的其余 23 个模块
 * （18.1.1.2 接口目录 / 18.1.1.3 调用监控 / 18.1.2.x Webhook / 18.1.3.x 数据交换 / 18.2.x 外部系统对接 /
 * 18.3.x 规则引擎 / 18.4.x AI 与智能）当前全部「未开发」，它们各自会在后续车道里带自己的
 * 迁移与端点。
 *
 * <p>⚠️ **本服务管的是「别人怎么接进来」，不是「我们怎么接别人」**：
 *
 * <ul>
 *   <li>卷 18 卷首红线：「**不提供任何绕过平台官方 API 的模拟登录能力**」—— 本服务**不**提供
 *       「代用户登录 Amazon / Shopify 后台抓数据」这类能力，18.2 的外部系统对接一律走对方
 *       官方 API 与 OAuth。
 *   <li>卷 18 卷首红线：「对外 API 严格按租户隔离 + 权限校验 + 限流 + 审计」—— 本服务自己
 *       就是这条红线的**第一道实现**：对外应用与凭证、授权范围、IP 白名单、调用配额、审计留痕。
 *   <li>跨服务一律走**授权 API / event**：不读别的服务的表、不建跨库 FK、不建跨库视图。
 * </ul>
 *
 * <p>上游是 17 组织权限与系统设置（identity）：租户与权限由 identity 依据**已认证成员关系**解析，
 * 本服务不信任客户端自称的租户。
 */
@SpringBootApplication
public class PlatformApplication {
  public static void main(String[] args) {
    SpringApplication.run(PlatformApplication.class, args);
  }
}
