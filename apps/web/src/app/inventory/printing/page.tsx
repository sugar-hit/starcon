import { LabelPrintingWorkspace } from '@/components/inventory/label-printing-workspace';

/**
 * 8.3.4.2 面单与标签打印。
 *
 * <p>蓝图：「打印面单、装箱单、SKU 条码、库位标签、箱唛，支持批量打印、模板配置、
 * 打印次数记录（平台对重复打印有要求）。」输出：「打印任务记录 + 打印内容（PDF/指令流）；
 * 打印日志。」以及红线：「面单打印次数需记录，部分平台对重复面单有明确规则。」
 *
 * <p>这是一个真实工作区而不是占位页：五种打印对象各自有模板与真渲染内容（ZPL 指令流 /
 * HTML 打印视图）、批量打印逐对象真计数、平台维度的重复打印上限与告警、
 * 强制重打必须留理由、以及 append-only 的打印凭据，全部落到 PostgreSQL 16，
 * 且「次数只增不减」「次数不得超过凭据条数」由数据库触发器强制。
 */
export default function LabelPrintingPage() {
  return <LabelPrintingWorkspace />;
}