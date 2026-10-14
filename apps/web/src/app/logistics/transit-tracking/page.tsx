import { TransitTrackingWorkspace } from '@/components/logistics/transit-tracking';

/**
 * 10.4.1.2 在途跟踪。
 *
 * <p>蓝图：「跟踪头程在途：位置、预计到港、延误预警、清关状态、目的仓收货确认，
 * 节点异常主动告警」；输出「在途看板 + 延误预警 + 到港提醒」；关系段原文
 * **「头程延误是断货的主要外因，预警必须提前到预计到港前若干天」**。
 *
 * <p>页面必须让三件事看得见：
 * ① **提前量口径常驻**：三档提前量来自阈值表且可在页面上改，改一行就换口径，不发版；
 * ② **剩余窗口与「已经晚了」分开显示**：负数是已过预计到港仍未到货，严重度完全不同；
 * ③ **外部同步未接入如实写明**：没有连接器就没有「同步中」按钮 —— 给一个点下去必然失败的
 * 按钮，比不给更糟。
 */
export default function TransitTrackingPage() {
  return <TransitTrackingWorkspace />;
}
