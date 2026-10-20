import { ExchangeRateWorkspace } from '@/components/pricing/exchange-rate-workspace';

/**
 * 5.2.2.1 汇率查询。
 *
 * <p>蓝图：「查询各币种对各本位币的汇率（来源：中行/央行/平台结算汇率，可配置），展示汇率日期与来源，
 * 支持手动录入特定日期的汇率。」输出「汇率记录（汇率、方向、生效日期、来源）」，并加粗
 * 「**汇率方向必须显式**（1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源）」。
 *
 * <p>这是一个真实工作区而不是占位页：方向规则声明、汇率台账（筛选 / 分页 / 方向显式）、
 * 手工录入（方向两栏必填）、汇率来源配置（可配置 / 可停用 / 初始化内置来源）、
 * 外部拉取（真发 HTTP，失败有明确错误态与失败日志）、各币种对各本位币的汇率面板，
 * 全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /pricing/rates}，与同 L3 的 5.2.2.2 汇率快照**共用** ——
 * 两个模块各占一个独立分区（见 {@code RATE_SECTIONS}），不重写彼此的页面骨架。
 * 未交付的兄弟分区在页面上**显式标成未交付**，不画空白框冒充已交付。
 *
 * <p>数据来源如实标注：汇率表归 catalog 服务（卷 05 定价与价目的主责服务），
 * 12 财务（finance 服务）当前不存在，本模块不建 finance 侧的表；本位币读 identity 的法人档案
 * （17.1.2.2），读不到时显示「读不到本位币」而不是空面板。
 */
export default function ExchangeRatePage() {
  return <ExchangeRateWorkspace />;
}
