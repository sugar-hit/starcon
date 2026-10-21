import { PlatformDisputeAppealWorkspace } from '@/components/aftersales/platform-dispute-appeal-workspace';

/**
 * 11.4.1.2 证据与申诉。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「组织举证材料（物流签收、沟通记录、商品照片、
 * 检测报告）打包提交申诉，记录平台回复与结果。」输入：「举证材料；申诉文本（可用模板）；提交动作。」
 * 输出：「申诉提交记录 + 平台回复 + 结果（胜诉/败诉/部分）。」
 * 加粗句：「<b>举证材料必须来自系统记录</b>（轨迹、沟通日志），临时拼凑的证据在申诉中往往不被采信。」
 *
 * <p><b>为什么这层有独立页面</b>：本模块是**平台纠纷处理人的工作面** ——
 * 组织证据包、写申诉说明、盯提交窗口、递申诉、接平台回复与结果，
 * 是一段**被时钟追着跑**的流程（上游 11.4.1.1 的举证时限一过就自动判负），
 * 不是一段被下游消费的声明。
 *
 * <p>路由挂在 {@code /aftersales/disputes} 之下（L3 11.4 平台纠纷与客诉），
 * 与上游 11.4.1.1 纠纷列表（{@code /aftersales/disputes/platform-disputes}）并列。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/platform-dispute-appeals} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意没有**「上传任意文件当举证材料」的入口：举证材料必须引用
 * 系统里已有的一条事实记录，临时拼凑的证据在申诉中往往不被采信。
 */
export default function AftersalesPlatformDisputeAppealsPage() {
  return <PlatformDisputeAppealWorkspace />;
}
