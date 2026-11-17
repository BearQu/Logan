/**
 * 设计令牌（唯一权威源）：全站色板/间距/圆角/字阶/阴影/状态色都从这里取。
 * 目的：消灭「每个页面各写一套颜色」导致的简陋与不一致。任何新页面禁止再写裸色值。
 */

/** 品牌与主色：蓝为主，配三端语义色。 */
export const brand = {
  primary: "#1d4ed8",
  primaryHover: "#1e40af",
  primarySoft: "#eff4ff",
  accent: "#0f766e",
  siderBg: "#0f172a",
  siderActive: "#1d4ed8",
  canvas: "#f5f7fa",
  card: "#ffffff",
  border: "#e5e9f0",
  borderStrong: "#d3dae6",
  text: "#111827",
  textSecondary: "#4b5563",
  textMuted: "#9ca3af",
  tableHead: "#f8fafc",
  hover: "#f1f5f9"
};

/** 语义色：成功/警告/危险/信息，严重性与处理状态一律用这套。 */
export const semantic = {
  danger: "#dc2626",
  dangerSoft: "#fef2f2",
  warning: "#d97706",
  warningSoft: "#fffbeb",
  success: "#059669",
  successSoft: "#ecfdf5",
  info: "#0284c7",
  infoSoft: "#f0f9ff"
};

/** 崩溃类型着色（全站统一，图表/标签/图例共用一份，避免同类型不同页不同色）。 */
export const kindColor = {
  java: "#1d4ed8",
  native: "#c2410c",
  anr: "#b45309",
  nsexception: "#7c3aed",
  mach: "#9333ea",
  jscrash: "#0891b2",
  cppcrash: "#be123c",
  freeze: "#a16207",
  app: "#64748b",
  error: "#dc2626",
  http: "#0f766e",
  other: "#94a3b8"
};

/** 严重性 → 颜色 + 文案（后端返回 critical/high/medium/low/unknown）。 */
export const severityMeta = {
  critical: { label: "致命", color: semantic.danger, soft: semantic.dangerSoft },
  high: { label: "高", color: semantic.danger, soft: semantic.dangerSoft },
  medium: { label: "中", color: semantic.warning, soft: semantic.warningSoft },
  low: { label: "低", color: semantic.info, soft: semantic.infoSoft },
  unknown: { label: "待定", color: brand.textMuted, soft: "#f8fafc" }
};

/** 崩溃处理状态（与后端 updateStatus 0..3 对齐）。 */
export const issueStatusMeta = {
  0: { label: "未处理", color: semantic.danger, soft: semantic.dangerSoft },
  1: { label: "处理中", color: semantic.warning, soft: semantic.warningSoft },
  2: { label: "已修复", color: semantic.success, soft: semantic.successSoft },
  3: { label: "已忽略", color: brand.textMuted, soft: "#f8fafc" }
};

/** 三端语义。 */
export const platformMeta = {
  1: { label: "Android", short: "安卓", color: "#16a34a" },
  2: { label: "iOS", short: "iOS", color: "#0f172a" },
  3: { label: "HarmonyOS", short: "鸿蒙", color: "#dc2626" }
};

/** 间距与圆角（4px 基准栅格）。 */
export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 };
export const radius = { sm: 4, md: 6, lg: 10, pill: 999 };

/** 字阶。 */
export const font = {
  display: 30,
  title: 20,
  h2: 16,
  body: 13,
  caption: 12,
  tiny: 11,
  weightBold: 600,
  mono: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
};

/** 阴影（只用两档，克制）。 */
export const shadow = {
  card: "0 1px 2px rgba(15,23,42,0.04), 0 1px 3px rgba(15,23,42,0.06)",
  popup: "0 6px 16px rgba(15,23,42,0.12)"
};

/** 卡片通用外观。 */
export const cardStyle = {
  background: brand.card,
  border: "1px solid " + brand.border,
  borderRadius: radius.lg,
  boxShadow: shadow.card,
  padding: space.xl
};

export const cardTitleStyle = {
  fontSize: font.h2,
  fontWeight: font.weightBold,
  color: brand.text,
  margin: 0
};

/** 数字格式化：null/undefined → 占位符（宁缺毋假：取不到绝不显示成 0）。 */
export function fmtNum(v, placeholder) {
  if (v === null || v === undefined || isNaN(v)) {
    return placeholder === undefined ? "-" : placeholder;
  }
  return Number(v).toLocaleString("en-US");
}

/** 百分比：null → 占位符；其余保留两位。 */
export function fmtRate(v, placeholder) {
  if (v === null || v === undefined || isNaN(v)) {
    return placeholder === undefined ? "-" : placeholder;
  }
  return Number(v).toFixed(2) + "%";
}

/** 时间戳（毫秒）→ YYYY-MM-DD HH:mm；0/null → 占位符。 */
export function fmtTime(ts) {
  if (!ts) {
    return "-";
  }
  const d = new Date(Number(ts));
  const p = (n) => (n < 10 ? "0" + n : "" + n);
  return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate())
    + " " + p(d.getHours()) + ":" + p(d.getMinutes());
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前，超过 30 天退化为日期。 */
export function fmtAgo(ts) {
  if (!ts) {
    return "-";
  }
  const diff = Date.now() - Number(ts);
  if (diff < 60_000) {
    return "刚刚";
  }
  if (diff < 3600_000) {
    return Math.floor(diff / 60_000) + " 分钟前";
  }
  if (diff < 86400_000) {
    return Math.floor(diff / 3600_000) + " 小时前";
  }
  if (diff < 30 * 86400_000) {
    return Math.floor(diff / 86400_000) + " 天前";
  }
  return fmtTime(ts);
}
