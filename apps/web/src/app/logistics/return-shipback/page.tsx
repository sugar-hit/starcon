import { ReturnShipbackWorkspace } from './return-shipback-workspace';

/**
 * 10.7.1.2 退运回国。
 *
 * <p>蓝图：「管理退运回国：<b>批量退运单（凑单/合箱/报关）</b>、国内收货仓、关税与申报、
 * 回国后的重新入库或报废；<b>支持海运拼箱的成本分摊</b>。」输入：待退运清单；
 * 报关资料（3.7）；运输方式与成本。输出：退运单 + 报关资料；回国收货记录 + 重新入库分录；
 * 退运成本。
 *
 * <p>这是一个真实工作区而不是占位页：待退运清单（勾选多行合单）、退运单列表、
 * 报关资料、成本与**逐行成本分摊明细**、回国收货、重新入库 / 报废分录、留痕，
 * 全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>🔴 路由取 {@code /logistics/return-shipback}，与 10.7.1.1 的 {@code /logistics/returns}
 * 分得开 —— 前者是「决定退运」，本页是「真的把货凑成一批退运回国」。
 *
 * <p>🔴 界面上不可让步的两条：**合单是真的**（一次提交多行），**成本分摊是真的**
 * （逐行权重 / 比例 / 金额 / 尾差，合计恒等于成本金额，页面还会做守恒自检）。
 */
export default function ReturnShipbackPage() {
  return <ReturnShipbackWorkspace />;
}
