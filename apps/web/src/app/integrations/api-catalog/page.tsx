'use client';

import { ApiCatalogWorkspace } from '@/components/integrations/api-catalog-workspace';

/**
 * 18.1 开放平台 → 18.1.1 API 管理 → 18.1.1.2 接口目录（卷 18 前端入口 `/integrations/api-catalog`）。
 *
 * <p>蓝图：「<strong>职责</strong>：对外开放 API 的目录与文档：按业务域列出接口、参数、返回、限流说明、示例；
 * 支持在线测试（沙箱环境）。<strong>输入</strong>：接口定义（OpenAPI 生成物）；测试请求。
 * <strong>输出</strong>：接口文档 + 测试结果。<strong>关系</strong>：上游 = 后端 OpenAPI spec（单一真相源）；
 * 下游 = 外部系统。<strong>接口契约由后端 spec 生成，文档不手写</strong>——手写文档必然与实现漂移。」
 *
 * <p>🔴 本页与 <code>/integrations</code>（18.1.1.1 应用与凭证）<strong>不是一件事</strong>：
 * 那一页管的是「谁可以接入我们」，本页管的是「我们对外提供哪些接口、怎么调」。
 * 两者只是同属卷 18 而已 —— 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 <strong>接口清单不是本页写出来的</strong>：它由后端从各服务的 OpenAPI spec
 * （<code>/v3/api-docs</code>）实时解析。本页只负责显示 —— 前端**不**缓存接口清单，
 * 更**不**手抄一份（手抄的那份必然与实现漂移，而漂移掉的恰恰是接入方唯一能信的东西）。
 */
export default function ApiCatalogPage() {
  return (
    <div className="space-y-4">
      <ApiCatalogWorkspace />
    </div>
  );
}
