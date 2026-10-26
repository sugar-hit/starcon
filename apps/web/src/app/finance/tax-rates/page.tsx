import { TaxRateWorkspace } from '@/components/finance/tax-rate-workspace';

/**
 * 12.6.1.1 税率配置。
 *
 * <p>蓝图 12-finance.md：「维护税率配置：目的国/地区的销售税率、VAT 税率、IOSS 适用税率、
 * 代扣代缴税；支持按店铺站点绑定默认税率与税码。」关系：「<b>税率必须带生效时间</b>，
 * 历史订单用当时税率，不能被新政策追溯修改。」
 *
 * <p>这是一个**真实工作区**：四个税种选项（连同口径说明）+ 税率记录（版本链）+
 * 版本对比 + 按生效时间取税率 + 店铺站点默认税率与税码绑定 + 州销售税（只读引用 channel），
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/tax-rates} —— 与 12.4.1.1 的 {@code /finance/cost-valuation}、
 * 12.5.2.1 的 {@code /finance/vouchers} **并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function TaxRatesPage() {
  return <TaxRateWorkspace />;
}
