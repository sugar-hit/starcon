import { SupplierReconciliationWorkspace } from '@/components/finance/supplier-reconciliation-workspace';

/**
 * 12.1.2.3 供应商对账（财务侧）。
 *
 * <p>蓝图 12-finance.md：「财务侧供应商对账：核对采购侧对账单（6.5.1）与收货/质检数据，
 * 差异处理与确认，最终生成应付依据。」输出：「对账确认结果 + 差异清单 + 应付生成依据。」
 * 加粗句：「<b>财务是最终确认方</b>，采购不能自行确认应付——这是职责分离。」
 *
 * <p>这是一个**真实工作区**：导入采购侧对账单（上游 6.5.1.1）+ 核对收货与质检（上游 6.3.2.2 /
 * 6.4.2.1）+ 差异清单 + 差异说明 + 裁决 + 财务确认 + 应付依据，加载 / 空 / 错误三态齐全，
 * **没有占位数据**。
 *
 * <p>路由取 {@code /finance/supplier-reconciliation} —— 与 12.1.2.1 的 {@code /finance/payables}、
 * 12.1.2.2 的 {@code /finance/payment-plans} **并列**而非嵌套：三个模块的页面各自独立，
 * 前端一眼能分清「单据」「计划」与「对账」。
 *
 * <p>页面<b>刻意没有</b>「标记已付」「执行付款」两个入口：付款属 12.2.2.1（未交付）；
 * 同样<b>没有</b>任何「采购侧自行确认应付」的入口 —— 那是本模块加粗句明令禁止的职责倒置。
 */
export default function FinanceSupplierReconciliationPage() {
  return <SupplierReconciliationWorkspace />;
}
