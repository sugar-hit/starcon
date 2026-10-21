import { PlatformDisputeWorkspace } from '@/components/aftersales/platform-dispute-workspace';

/**
 * 11.4.1.1 平台纠纷列表。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、
 * 速卖通纠纷）：类型、金额、平台时限、举证要求、我方立场、结果记录。」
 * 输出：「纠纷单 + 时效倒计时 + 举证清单。」
 * 加粗句：「<b>平台纠纷有严格举证时限</b>，超期自动判败，是硬性时效要求。」
 *
 * <p><b>为什么这层有独立页面</b>：本模块是**平台纠纷处理人的工作面** ——
 * 登记纠纷、列举证清单、盯倒计时、提交举证、接平台裁决，
 * 是一段**被时钟追着跑**的流程，不是一段被下游消费的声明。
 * 蓝图加粗句「超期自动判败」在界面上必须看得见：倒计时由举证截止时刻现算，
 * 已超期的单据直接显示成「已超期」。
 *
 * <p>路由挂在 {@code /aftersales/disputes} 之下（L3 11.4 平台纠纷与客诉），
 * 与 L3 11.4.2 客诉工单（11.4.2.1，未开发）并列。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/platform-disputes} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意没有**「手动标记超期判负」的入口：超期自动判败是**系统做的**
 * （后端 DB 守卫①），一个能提前把自己判负的按钮只会让人用它来提前结案。
 */
export default function AftersalesPlatformDisputesPage() {
  return <PlatformDisputeWorkspace />;
}
