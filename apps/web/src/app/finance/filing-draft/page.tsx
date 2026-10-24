import { FilingDraftWorkspace } from '@/components/finance/filing-draft-workspace';

/**
 * 12.6.1.2 VAT/IOSS 申报数据。
 *
 * <p>蓝图 12-finance.md：「按辖区与周期汇总申报所需数据： taxable 销售额、已收 VAT、IOSS 订单与
 * 代收金额、可抵扣进项，导出申报文件或对接申报接口。」输出：「申报底稿 + 汇总报表 + 可导出文件。」
 * 关系：「<b>本系统提供申报底稿，不替代税务申报动作</b>——申报主体与责任在用户，
 * 系统保证数据完整可核。」
 *
 * <p>这是一个**真实工作区**：取数生成底稿（辖区走受控码表、税率按生效时间取）+
 * 底稿列表 + 按币种分组的汇总报表 + 数据来源可用性 + CSV / JSON **真文件**导出 + 导出留痕 +
 * 能力声明，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/filing-draft} —— 与 12.6.1.1 的 {@code /finance/tax-rates}、
 * 12.5.2.1 的 {@code /finance/vouchers} **并列**而非嵌套：那些是别的模块的页面
 * （别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function FilingDraftPage() {
  return <FilingDraftWorkspace />;
}
