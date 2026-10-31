'use client';

import { ImportExportWorkspace } from '@/components/integrations/io-task-workspace';

/**
 * 18.1 开放平台 → 18.1.3 数据交换 → 18.1.3.1 导入导出任务（卷 18 前端入口 `/integrations/io-tasks`）。
 *
 * <p>蓝图：「<strong>职责</strong>：统一的数据导入导出通道：任务创建（选择数据域与范围）、异步执行、
 * 进度跟踪、结果下载、失败行报告、模板下载。<strong>输入</strong>：导出/导入请求；文件上传。
 * <strong>输出</strong>：任务记录 + 文件（带有效期）+ 错误报告。<strong>关系</strong>：上游 = 全部业务域；
 * 下游 = 用户本地文件。<strong>大文件一律异步</strong>，并在下载链接上设置有效期，避免存储无限增长。」
 *
 * <p>🔴 本页与 <code>/integrations</code>（18.1.1.1 应用与凭证）、
 * <code>/integrations/webhooks</code>（18.1.2.1 事件订阅）、
 * <code>/integrations/deliveries</code>（18.1.2.2 投递记录）<strong>不是一件事</strong>：
 * 那三页管「谁可以接入我们 / 事件怎么订阅 / 事件投没投出去」，
 * 本页管「<strong>数据怎么进、怎么出</strong>」。四者只是同属卷 18 而已 ——
 * 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 <strong>「上游 = 全部业务域」在页面上是可见的</strong>：
 * 「数据域目录」逐格列出每个域接没接通、数据从哪来、没接通缺什么归谁 ——
 * 不接通的那几格**照样出现**并标「未接通」，而不是从清单里悄悄删掉。
 */
export default function ImportExportTasksPage() {
  return (
    <div className="space-y-4">
      <ImportExportWorkspace />
    </div>
  );
}
