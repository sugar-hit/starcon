import Link from 'next/link';
import { ReceivableWorkspace } from '@/components/finance/receivable-workspace';

/**
 * 12.1.1.1 应收单管理。
 *
 * <p>蓝图 12-finance.md：「管理应收账款：来源（平台结算、平台代扣、B2B 客户账期、其他）、
 * 应收对象、金额与币种、到期日、账龄、明细核销状态。」输出：「应收单 + 明细行 + 账龄分布；
 * 生成催收任务。」
 * 加粗句：「<b>未发货订单不默认等于收入</b>——应收确认政策需单独配置（实体目录 finance 段明确）。」
 *
 * <p>这是一个**真实工作区**：应收确认政策 + 按币种的汇总 + 账龄分布 + 应收单列表与详情
 * （明细行 / 事件流）+ 催收任务 + 人工录入，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance} —— 与卷 12 的其余入口（12.2 收付款 / 12.3 结算 / 12.5 总账 …）
 * **并列**而非嵌套：那些模块尚未交付，等它们落地时各自有自己的子路径。
 *
 * <p>🔴 **同服务的 12.2.1.1 收款单在 {@code /finance/receipts}**（不是本页的一部分）：
 * 收款单与应收单各有各的单据、单据号前缀与未核销口径，混在一个页面里会让「哪张单是应收、
 * 哪张是收款」变得说不清。下面给一个入口过去。
 *
 * <p>页面<b>刻意没有</b>「用平台结算建应收」「手工建 B2B 账期应收」「核销」「坏账核销」
 * 四个入口：前两个的上游/事实来源还不成立，后两个（12.2.1.2 / 12.1.1.3）**均未交付**；
 * 能力声明会如实把它们报成 {@code available=false} 并写明归属模块。
 */
export default function FinancePage() {
  return (
    <div>
      <div className="px-6 pt-6">
        <Link
          href="/finance/receipts"
          className="text-copy-13 text-muted-foreground underline-offset-2 hover:underline"
        >
          收款单（12.2.1.1）→ 登记与跟踪资金收入、按币种看未核销余额
        </Link>
      </div>
      <ReceivableWorkspace />
    </div>
  );
}
