'use client';

import { DataMigrationWorkspace } from '@/components/integrations/data-migration-workspace';

/**
 * 18.1 开放平台 → 18.1.3 数据交换 → 18.1.3.2 数据迁移（卷 18 前端入口 `/integrations/data-migrations`）。
 *
 * <p>蓝图：「<strong>职责</strong>：支持从其他 ERP 迁移历史数据：映射配置（外部字段 → 内部模型）、
 * 试运行预览、正式迁移、失败回滚与核对报告。<strong>输入</strong>：源数据文件；字段映射配置。
 * <strong>输出</strong>：迁移任务 + 迁移核对报告（成功/失败/跳过数量与原因）。
 * <strong>关系</strong>：上游 = 18.1.3.1；下游 = 各业务域。
 * <strong>历史数据迁移必须支持试运行</strong>，直接正式跑会产生大量脏数据难以回退。」
 *
 * <p>🔴 本页与 <code>/integrations/io-tasks</code>（18.1.3.1 导入导出任务）<strong>不是一件事</strong>：
 * 那边管「把本系统的数据导出去 / 把文件导进来」，本页管「<strong>把别的 ERP 的历史数据
 * 迁进本系统的业务库</strong>」—— 方向、状态机、回滚语义都不同。
 * 四者（同属卷 18）只是同属卷 18 而已 —— 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 <strong>「试运行必须支持」在页面上是可见的</strong>：发起面板把「试运行」与
 * 「正式迁移」摆成两个并列选项，默认落在试运行上 —— 默认值会让「直接正式跑」变成一次误点。
 */
export default function DataMigrationsPage() {
  return (
    <div className="space-y-4">
      <DataMigrationWorkspace />
    </div>
  );
}
