import { LaborEfficiencyWorkspace } from '@/components/inventory/labor-efficiency-workspace';

/**
 * 8.5.1.1 作业效率（人效）。
 *
 * <p>蓝图：「统计仓库人效：人均日拣货件数/单量、拣货行走距离、打包时效、复核准确率、差错率，
 * 按人按日统计。」输入：「作业任务记录（操作人、时间、类型）；人员配置。」
 * 输出：「人效报表 + 排名 + 趋势。」以及红线
 * 「**人效数据用于提成核算与排班，需明确口径避免争议**（ERP 人效报表是绩效争议高发区）」。
 *
 * <p>这是一个真实工作区而不是占位页：派工 / 开工 / 完成 / 异常上报都落到 PostgreSQL 16 的
 * 作业任务表（状态机与「定稿后计量冻结」由数据库触发器强制），人效聚合全部在 SQL 里算
 * （sum / count / avg / filter），报表、排名、趋势与五个指标的精确口径都从真实端点读取。
 */
export default function LaborEfficiencyPage() {
  return <LaborEfficiencyWorkspace />;
}
