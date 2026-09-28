/**
 * 城市地标天际线剪影。
 *
 * 每个城市一条 path，画在 120×40 的坐标系里，底部 y=40 贴地。
 * 剪影只表达"这座城市有什么 recognizable 的轮廓"，不追求写实——
 * 时间带只有 ~105px 高，剪影是氛围而不是插画。
 *
 * 设计取向：单一形状语言（塔/楼/穹顶/桥），负空间为主，
 * 与品牌色单一色相配合，不引入彩色。
 */

/** 剪影画布：宽 120、高 40，底边贴地 */
export const SKYLINE_VIEW = { width: 120, height: 40, ground: 40 } as const;

export interface Skyline {
  /** 城市中文名，与 world-clock 的 city 字段对应 */
  city: string;
  /** 无障碍描述：这是什么地方 */
  landmark: string;
  /** SVG path，坐标系 120×40 */
  path: string;
}

/** 地标基座辅助：矩形（x, y 为左上角） */
function box(x: number, y: number, w: number, h: number): string {
  return `M${x} ${y}H${x + w}V${y + h}H${x}Z`;
}

/** 尖顶三角（底边中点 cx，底宽 bw，底边 y，峰顶 y） */
function spire(cx: number, bw: number, y: number, peakY: number): string {
  return `M${cx - bw / 2} ${y}L${cx} ${peakY}L${cx + bw / 2} ${y}Z`;
}

/** 圆顶（圆心 cx，底边 y，半径 r） */
function dome(cx: number, y: number, r: number): string {
  return `M${cx - r} ${y}A${r} ${r} 0 0 1 ${cx + r} ${y}Z`;
}

const S = SKYLINE_VIEW;

export const SKYLINES: readonly Skyline[] = [
  {
    city: '北京',
    landmark: '天安门与央视大楼',
    // 左：故宫式台基与屋檐；中：央视大楼的方形环
    path:
      box(0, 32, 14, 8) +
      spire(7, 14, 32, 26) +
      box(16, 28, 20, 12) +
      box(20, 24, 12, 4) +
      // 央视大楼：两座带环的方塔
      box(48, 6, 12, 34) +
      box(50, 18, 8, 10) +
      box(64, 12, 10, 28) +
      box(66, 20, 6, 8) +
      box(84, 30, 36, 10),
  },
  {
    city: '纽约',
    landmark: '曼哈顿天际线与自由女神像',
    path:
      // 帝国大厦的阶梯收分
      box(10, 8, 16, 32) + box(13, 4, 10, 4) + spire(18, 4, 4, 0) +
      box(30, 20, 12, 20) + box(33, 16, 6, 4) +
      // 自由女神：底座 + 举火炬的手臂
      box(54, 30, 10, 10) + box(56, 20, 6, 10) + spire(59, 6, 20, 14) +
      box(63, 8, 3, 12) + spire(64.5, 4, 8, 2) +
      box(72, 24, 14, 16) + box(90, 28, 30, 12),
  },
  {
    city: '洛杉矶',
    landmark: '好莱坞标志与银滩',
    path:
      // 山体
      `M0 32Q26 18 50 28Q76 34 100 24L120 30V${S.ground}H0Z` +
      // 好莱坞标志：柱阵撑起一块牌匾
      `M40 22H84V8H40Z` + `M44 22V34M52 22V34M60 22V34M68 22V34M76 22V34` +
      spire(62, 8, 8, 2) +
      box(88, 28, 32, 12),
  },
  {
    city: '芝加哥',
    landmark: '威利斯大厦与湖滨天际线',
    path:
      // 威利斯：束腰双筒
      box(28, 4, 10, 36) + box(42, 4, 10, 36) + box(24, 12, 26, 4) + box(24, 30, 26, 4) +
      spire(40, 6, 4, 0) +
      // 湖滨楼群
      box(0, 26, 22, 14) + box(58, 18, 14, 22) + box(66, 12, 6, 6) +
      box(78, 24, 16, 16) + box(100, 30, 20, 10),
  },
  {
    city: '多伦多',
    landmark: '加拿大国家电视塔',
    path:
      // CN 塔：长杆 + 碟形观景层 + 天线
      box(52, 2, 8, 38) + `M42 16H70L64 26H48Z` + box(54, 0, 4, 4) +
      box(20, 26, 24, 14) + box(70, 24, 22, 16) + box(98, 30, 22, 10),
  },
  {
    city: '温哥华',
    landmark: '加拿大广场与海岸山',
    path:
      // 海岸山
      `M0 30L18 12L30 22L44 8L60 30Z` +
      // 加拿大广场：波浪屋顶
      `M22 ${S.ground}V26Q30 18 38 26V${S.ground}ZM40 ${S.ground}V24Q48 16 56 24V${S.ground}Z` +
      // 市区方楼
      box(64, 18, 14, 22) + box(84, 24, 16, 16) + box(104, 30, 16, 10),
  },
  {
    city: '墨西哥城',
    landmark: '独立天使纪念碑与主教座堂',
    path:
      // 纪念碑：底座 + 柱身 + 顶天使
      box(18, 30, 18, 10) + box(22, 12, 10, 18) + dome(27, 12, 5) +
      `M22 4L14 8M32 4L40 8M27 2V0` +
      // 主教座堂双塔
      box(48, 16, 10, 24) + spire(53, 10, 16, 6) + box(62, 20, 14, 20) + dome(69, 20, 7) +
      box(82, 24, 18, 16) + box(104, 30, 16, 10),
  },
  {
    city: '伦敦',
    landmark: '大本钟与伦敦眼',
    path:
      // 大本钟塔
      box(12, 6, 10, 34) + spire(17, 10, 6, 0) + box(14, 12, 6, 6) +
      // 圣保罗穹顶
      dome(40, 26, 12) + box(28, 26, 24, 14) + spire(40, 4, 14, 2) +
      // 伦敦眼：环形（外弧正向、内弧反向挖空）
      `M72 34A14 14 0 0 1 100 34M76 34A10 10 0 0 0 96 34M86 34L86 ${S.ground}` +
      box(104, 28, 16, 12),
  },
  {
    city: '巴黎',
    landmark: '埃菲尔铁塔',
    path:
      // 四条腿 + 塔身收分 + 顶层平台
      `M22 ${S.ground}L34 18L34 14L30 14L38 6L42 6L50 14L46 14L46 18L58 ${S.ground}` +
      `M34 22L46 22M36 18L36 ${S.ground}M44 18L44 ${S.ground}` +
      `M38 6L38 0L42 0L42 6Z` +
      box(70, 26, 22, 14) + box(74, 20, 14, 6) + box(98, 30, 22, 10),
  },
  {
    city: '法兰克福',
    landmark: '美因河畔天际线',
    path:
      box(6, 24, 12, 16) + box(20, 16, 10, 24) + spire(25, 10, 16, 8) +
      // 银行区方塔群
      box(32, 20, 14, 20) + box(36, 12, 6, 8) +
      box(48, 10, 12, 30) + box(51, 4, 6, 6) +
      box(62, 22, 16, 18) + box(80, 26, 16, 14) + box(98, 30, 22, 10),
  },
  {
    city: '阿姆斯特丹',
    landmark: '运河房山墙',
    path:
      // 窄高房 + 三角山墙（运河屋的标志）
      box(4, 22, 12, 18) + spire(10, 12, 22, 14) +
      box(18, 16, 11, 24) + spire(23.5, 11, 16, 6) +
      box(31, 24, 12, 16) + spire(37, 12, 24, 16) +
      // 教堂钟塔
      box(46, 8, 10, 32) + spire(51, 10, 8, 0) +
      box(60, 26, 24, 14) + box(88, 30, 32, 10),
  },
  {
    city: '马德里',
    landmark: '皇宫与普拉多博物馆',
    path:
      box(4, 26, 22, 14) + box(8, 20, 14, 6) + dome(15, 20, 7) +
      // 主广场方塔
      box(30, 12, 12, 28) + spire(36, 12, 12, 4) + box(32, 18, 8, 6) +
      box(46, 24, 14, 16) + box(64, 18, 12, 22) + spire(70, 12, 18, 12) +
      box(80, 28, 18, 12) + box(100, 31, 20, 9),
  },
  {
    city: '斯德哥尔摩',
    landmark: '市政厅与斯德哥尔摩大教堂',
    path:
      // 市政厅：方体 + 三座尖塔
      box(10, 20, 26, 20) + spire(16, 8, 20, 6) + spire(23, 8, 20, 2) + spire(30, 8, 20, 6) +
      // 大教堂尖塔
      box(44, 14, 12, 26) + spire(50, 12, 14, 0) + box(46, 20, 8, 8) +
      box(62, 24, 20, 16) + box(86, 28, 34, 12),
  },
  {
    city: '新加坡',
    landmark: '滨海湾金沙与艺术科学博物馆',
    path:
      // 金沙：三塔 + 顶部长船
      box(6, 18, 8, 22) + box(20, 12, 8, 28) + box(34, 18, 8, 22) +
      box(4, 14, 40, 4) +
      // 艺术科学博物馆：一只"帆"
      `M56 ${S.ground}L56 26Q68 18 80 10L80 ${S.ground}Z` +
      box(86, 24, 14, 16) + box(104, 28, 16, 12),
  },
  {
    city: '曼谷',
    landmark: '玉佛寺尖顶',
    path:
      // 多层塔尖（金寺风格）
      box(8, 24, 26, 16) + spire(21, 26, 24, 2) + spire(21, 16, 14, 0) +
      box(40, 20, 18, 20) + spire(49, 18, 20, 6) +
      box(64, 26, 20, 14) + spire(74, 20, 26, 14) +
      box(90, 22, 30, 18),
  },
  {
    city: '吉隆坡',
    landmark: '双子塔',
    path:
      // 两座带天线与连廊的塔
      box(16, 4, 16, 36) + spire(24, 6, 4, 0) + box(20, 14, 8, 12) +
      box(40, 4, 16, 36) + spire(48, 6, 4, 0) + box(44, 14, 8, 12) +
      box(32, 18, 8, 3) +
      box(64, 26, 22, 14) + box(90, 30, 30, 10),
  },
  {
    city: '雅加达',
    landmark: '伊斯蒂赫拉尔清真寺圆顶',
    path:
      dome(24, 22, 16) + spire(24, 3, 6, 0) + box(8, 22, 32, 18) +
      box(4, 28, 5, 12) + box(39, 28, 5, 12) +
      box(48, 18, 14, 22) + dome(55, 18, 7) +
      box(68, 26, 20, 14) + box(92, 30, 28, 10),
  },
  {
    city: '马尼拉',
    landmark: '黎刹岫与圣马特奥教堂',
    path:
      // 教堂双塔
      box(14, 8, 11, 32) + spire(19.5, 11, 8, 0) + box(16, 14, 7, 8) +
      box(30, 16, 14, 24) + dome(37, 16, 7) +
      box(50, 10, 10, 30) + spire(55, 10, 10, 0) + box(52, 16, 6, 8) +
      box(66, 26, 22, 14) + box(92, 30, 28, 10),
  },
  {
    city: '胡志明市',
    landmark: '西贡圣母大教堂',
    path:
      box(20, 14, 24, 26) + spire(32, 24, 14, 0) + box(24, 20, 16, 8) +
      box(12, 26, 8, 14) + box(44, 26, 8, 14) +
      box(58, 22, 18, 18) + box(80, 26, 18, 14) + box(102, 30, 18, 10),
  },
  {
    city: '东京',
    landmark: '东京晴空塔与富士山',
    path:
      // 富士：双峰 + 雪顶
      `M4 34L26 10L34 20L44 6L60 34Z` + `M26 10L31 15L21 15Z` +
      // 晴空塔：细长 + 观景台
      box(74, 2, 8, 38) + box(70, 12, 16, 4) + box(72, 22, 12, 3) +
      box(90, 26, 30, 14),
  },
  {
    city: '首尔',
    landmark: '景福宫与南山塔',
    path:
      // 景福宫：层叠屋檐
      box(6, 24, 30, 16) + box(10, 18, 22, 6) + box(14, 12, 14, 6) + spire(21, 14, 12, 4) +
      // 南山塔
      box(46, 6, 12, 34) + dome(52, 6, 6) + box(48, 16, 8, 6) +
      box(64, 24, 20, 16) + box(88, 28, 32, 12),
  },
  {
    city: '孟买',
    landmark: '印度门',
    path:
      // 拱门：两柱 + 顶部横梁 + 圆弧
      `M18 ${S.ground}V16A20 20 0 0 1 58 16V${S.ground}H48V20A10 10 0 0 0 28 20V${S.ground}Z` +
      box(14, 12, 48, 5) + box(24, 2, 28, 10) + dome(38, 2, 6) +
      box(70, 26, 20, 14) + box(94, 30, 26, 10),
  },
  {
    city: '迪拜',
    landmark: '哈利法塔',
    path:
      // Y 字形：双翼收成塔尖
      `M18 ${S.ground}L26 30L34 12L40 12L38 30L46 ${S.ground}Z` +
      `M34 12L36 0L40 0L42 12Z` +
      box(54, 20, 14, 20) + box(72, 24, 16, 16) + box(92, 28, 28, 12),
  },
  {
    city: '阿拉木图',
    landmark: '科克托别山与电视塔',
    path:
      // 山
      `M0 34L22 12L34 24L46 16L60 34Z` +
      // 电视塔：细杆 + 碟形观景层
      box(72, 4, 6, 36) + box(66, 16, 18, 5) + box(68, 26, 14, 4) +
      box(90, 26, 30, 14),
  },
  {
    city: '德黑兰',
    landmark: '自由广场与波斯柱廊',
    path:
      // 阿帕达纳柱廊：柱列
      `M6 24H114V28H6Z` +
      `M10 28H16V${S.ground}H10ZM22 28H28V${S.ground}H22ZM34 28H40V${S.ground}H34ZM46 28H52V${S.ground}H46ZM58 28H64V${S.ground}H58ZM70 28H76V${S.ground}H70ZM82 28H88V${S.ground}H82ZM94 28H100V${S.ground}H94ZM106 28H112V${S.ground}H106Z` +
      box(40, 12, 12, 12) + spire(46, 12, 12, 4),
  },
  {
    city: '圣保罗',
    landmark: '基督像与山体',
    path:
      // 山坡
      `M0 30Q30 18 62 26Q92 32 120 24V${S.ground}H0Z` +
      // 基督像：头 + 张开双臂 + 身
      `M62 4A5 5 0 1 1 62 14A5 5 0 0 1 62 4ZM64 15H72V30H64Z` +
      `M44 20L64 15L64 19L46 25ZM72 15L92 20L90 25L72 19Z` +
      box(58, 28, 20, 12),
  },
  {
    city: '布宜诺斯艾利斯',
    landmark: '自由纪念碑与国会大厦',
    path:
      // 自由纪念碑：方尖碑 + 台阶
      box(30, 4, 10, 28) + box(26, 32, 18, 8) + box(20, 36, 30, 4) +
      // 国会：穹顶 + 基座
      dome(80, 18, 12) + box(68, 18, 24, 22) + box(72, 24, 16, 8) + spire(80, 3, 6, 0) +
      box(96, 30, 24, 10),
  },
  {
    city: '圣地亚哥',
    landmark: '安第斯山与主教座堂',
    path:
      // 安第斯山
      `M0 22L18 4L30 16L42 2L56 22L70 8L84 24L100 10L120 24V${S.ground}H0Z` +
      // 主教座堂双塔
      box(40, 20, 9, 20) + spire(44.5, 9, 20, 10) +
      box(54, 20, 9, 20) + spire(58.5, 9, 20, 10) +
      box(48, 28, 8, 12),
  },
  {
    city: '悉尼',
    landmark: '悉尼歌剧院',
    path:
      // 帆状贝壳：多个弧
      `M10 ${S.ground}Q22 6 34 ${S.ground}ZM32 ${S.ground}Q46 2 60 ${S.ground}ZM58 ${S.ground}Q72 6 86 ${S.ground}Z` +
      // 餐厅 + 台阶
      box(6, 34, 80, 6) + box(90, 26, 30, 14),
  },
  {
    city: '墨尔本',
    landmark: '联邦广场与 Melbourne Skydeck',
    path:
      // 联邦广场的尖锥
      spire(20, 14, 32, 6) + box(13, 32, 14, 8) +
      // 尖塔
      box(38, 10, 10, 30) + spire(43, 10, 10, 0) +
      // 广场柱廊
      box(54, 24, 20, 16) + `M58 24V14M62 24V12M66 24V14M70 24V12` +
      box(80, 26, 18, 14) + box(102, 30, 18, 10),
  },
  {
    city: '奥克兰',
    landmark: '天空塔',
    path:
      // 天空塔：细杆 + 两道碟层
      box(52, 2, 8, 38) + `M44 12H68L64 20H48Z` + `M46 24H66L62 30H50Z` +
      box(22, 26, 24, 14) + box(70, 26, 26, 14),
  },
  {
    city: '开普敦',
    landmark: '桌山与议会大厦',
    path:
      // 桌山：平顶
      `M0 30L12 8L30 6L58 8L70 12L82 30Z` +
      // 议会：对称巴洛克 + 圆顶
      box(30, 26, 26, 14) + dome(43, 26, 9) + spire(43, 3, 17, 12) +
      box(84, 24, 18, 16) + box(88, 18, 10, 6) + box(106, 28, 14, 12),
  },
  {
    city: '拉各斯',
    landmark: '第三大陆大桥与 Eko 塔',
    path:
      // 斜拉桥：桥面 + 斜索
      box(0, 26, 120, 4) + `M30 30L30 8M30 8L18 26M30 8L42 26M30 8L8 26M30 8L54 26` +
      box(26, 4, 8, 26) + spire(30, 8, 4, 0) +
      // Eko 塔
      box(78, 8, 14, 32) + box(80, 16, 10, 10) + box(100, 26, 20, 14),
  },
  {
    city: '内罗毕',
    landmark: '国际会议中心与现代高楼',
    path:
      // KICC：圆盘塔身
      `M14 22A10 10 0 0 1 34 22V${S.ground}H14Z` + box(12, 16, 24, 5) + box(22, 4, 4, 12) +
      // 玻璃高楼
      box(46, 12, 14, 28) + box(49, 6, 8, 6) +
      box(66, 20, 16, 20) + box(86, 26, 16, 14) + box(104, 30, 16, 10),
  },
];

/** 按城市名取剪影；未收录的城市返回 undefined（调用方需有兜底） */
export function skylineFor(city: string): Skyline | undefined {
  return SKYLINES.find((item) => item.city === city);
}

/** 覆盖率统计，供测试与文档核对 */
export function skylineCoverage(cities: readonly string[]): {
  covered: string[];
  missing: string[];
} {
  const covered: string[] = [];
  const missing: string[] = [];
  for (const city of cities) {
    (skylineFor(city) ? covered : missing).push(city);
  }
  return { covered, missing };
}
