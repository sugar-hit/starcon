import Link from 'next/link';
import { ReceivableWorkspace } from '@/components/finance/receivable-workspace';

/**
 * 12 财务核算（卷 12）的入口。
 *
 * <p>当前已交付的两个模块各自有自己的页面与路由，**并列**而非嵌套：
 *
 * <ul>
 *   <li>{@code /finance} —— 12.1.1.1 应收单管理（应收 / 核销 / 账龄）；</li>
 *   <li>{@code /finance/payables} —— 12.1.2.1 应付单管理（应付 / 预付款 / 未付清单）。</li>
 * </ul>
 *
 * <p>「收」与「付」在路由上就分开 —— 页面上一眼能分清，不靠面包屑。
 *
 * <p>本页主体是 12.1.1.1 应收单管理：
 * 蓝图 12-finance.md：「管理应收账款：来源（平台结算、平台代扣、B2B 客户账期、其他）、
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
 * <p>页面<b>刻意没有</b>「用平台结算建应收」「手工建 B2B 账期应收」「核销」「坏账核销」
 * 四个入口：前两个的上游/事实来源还不成立，后两个（12.2.1.2 / 12.1.1.3）**均未交付**；
 * 能力声明会如实把它们报成 {@code available=false} 并写明归属模块。
 */
export default function FinancePage() {
  return (
    <div>
      <nav className="border-border flex items-center gap-4 border-b px-6 py-3 text-sm">
        <Link href="/finance" className="font-medium">
          应收单管理
        </Link>
        <Link href="/finance/payables" className="text-muted-foreground">
          应付单管理
        </Link>
      </nav>
      <ReceivableWorkspace />
    </div>
  );
}
