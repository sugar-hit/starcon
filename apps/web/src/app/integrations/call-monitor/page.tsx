'use client';

import { CallMonitorWorkspace } from '@/components/integrations/call-monitor-workspace';

/**
 * 18.1 开放平台 → 18.1.1 API 管理 → 18.1.1.3 调用监控（卷 18 前端入口 `/integrations/call-monitor`）。
 *
 * <p>蓝图：「<strong>职责</strong>：监控 API 调用：QPS、错误率、限流命中、慢调用、按应用与接口维度分析，异常告警。
 * <strong>输入</strong>：调用埋点；配额配置。<strong>输出</strong>：调用统计 + 告警。
 * <strong>关系</strong>：上游 = API 网关；下游 = 1 工作台告警、18.1.1.1 配额调整。
 * <strong>外部系统调用错误要能被快速定位</strong>，否则对接方只会说"你们接口有问题"。」
 *
 * <p>🔴 本页与 <code>/integrations</code>（18.1.1.1 应用与凭证）、<code>/integrations/api-catalog</code>
 * （18.1.1.2 接口目录）<strong>不是一件事</strong>：那一页管「谁可以接入我们」，
 * 目录页管「我们对外提供哪些接口」，本页管「<strong>调得怎么样、出没出事</strong>」。
 * 三者只是同属卷 18 而已 —— 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 <strong>页面上的数字全部来自真实埋点</strong>：埋点由网关入口
 * <code>/open/v1/**</code> 落库，前端<b>不</b>缓存、<b>不</b>手写统计口径。
 * 阈值口径由后端随 <code>/options</code> 一起给出，页面只负责显示。
 */
export default function CallMonitorPage() {
  return (
    <div className="space-y-4">
      <CallMonitorWorkspace />
    </div>
  );
}
