import React from "react";
import {
  brand, semantic, kindColor, severityMeta, issueStatusMeta, platformMeta,
  radius, shadow, space, font, cardStyle, fmtNum, fmtRate
} from "../../theme";

/**
 * 全站统一 UI 原子组件（零图表依赖，全部手写 SVG / div）。
 * 新页面必须复用这些组件，不要再各自写一套卡片、标签、空态。
 */

const titleStyle = { fontSize: font.h2, fontWeight: font.weightBold, color: brand.text, margin: 0 };
const hintStyle = { fontSize: font.caption, color: brand.textMuted, marginTop: 2 };

/** 面板卡片：统一标题栏 + 右侧操作区 + 内容区。 */
export function Panel(props) {
  const { title, extra, hint, children, style, bodyStyle, loading } = props;
  return (
    <section className="logan-panel" style={Object.assign({}, cardStyle, style)}>
      {(title || extra) ? (
        <header className="logan-panel-head">
          <div>
            {title ? <h3 style={titleStyle}>{title}</h3> : null}
            {hint ? <div style={hintStyle}>{hint}</div> : null}
          </div>
          {extra ? <div className="logan-panel-extra">{extra}</div> : null}
        </header>
      ) : null}
      <div style={Object.assign({}, bodyStyle, loading ? { opacity: 0.45 } : null)}>
        {children}
      </div>
    </section>
  );
}

const tonePreset = (tone) => {
  if (tone === "danger") return { c: semantic.danger, s: semantic.dangerSoft };
  if (tone === "warning") return { c: semantic.warning, s: semantic.warningSoft };
  if (tone === "success") return { c: semantic.success, s: semantic.successSoft };
  if (tone === "info") return { c: semantic.info, s: semantic.infoSoft };
  return { c: brand.textSecondary, s: "#f1f5f9" };
};

/** 标签：统一圆角/字号/底色。 */
export function Tag(props) {
  const { tone, color, soft, children, title } = props;
  const preset = tonePreset(tone);
  const fg = color || preset.c;
  const bg = soft || preset.s;
  return (
    <span title={title} className="logan-tag" style={{ color: fg, background: bg, borderColor: fg, borderRadius: radius.sm }}>
      {children}
    </span>
  );
}

export function KindTag({ kind }) {
  const k = String(kind || "").toLowerCase();
  const c = kindColor[k] || kindColor.other;
  return <Tag color={c} soft={c + "14"}>{k || "unknown"}</Tag>;
}

export function SeverityTag({ severity }) {
  const m = severityMeta[String(severity || "").toLowerCase()] || severityMeta.unknown;
  return <Tag color={m.color} soft={m.soft}>{m.label}</Tag>;
}

export function StatusTag({ status }) {
  const m = issueStatusMeta[Number(status) || 0] || issueStatusMeta[0];
  return <Tag color={m.color} soft={m.soft}>{m.label}</Tag>;
}

export function PlatformTag({ platform }) {
  const m = platformMeta[Number(platform)];
  if (!m) return <Tag>未知端</Tag>;
  return <Tag color={m.color} soft={m.color + "14"}>{m.short}</Tag>;
}

/** 波动徽标：正数红▲、负数绿▼、0 持平、null 显示「新增」由 isNew 决定。 */
export function DeltaTag({ value, isNew }) {
  if (isNew) return <Tag tone="danger">新增</Tag>;
  if (value === null || value === undefined || isNaN(value)) {
    return <span style={{ color: brand.textMuted, fontSize: font.caption }}>-</span>;
  }
  const v = Number(value);
  if (v === 0) return <Tag>持平</Tag>;
  const up = v > 0;
  return (
    <span style={{ color: up ? semantic.danger : semantic.success, fontSize: font.caption, fontWeight: font.weightBold }}>
      {up ? "▲ " : "▼ "}{Math.abs(v).toFixed(2)}%
    </span>
  );
}

/** 空态：统一文案 + 可选行动，绝不出现空白面板。 */
export function EmptyState(props) {
  const { text = "暂无数据", hint, action, height = 120 } = props;
  return (
    <div style={{ height, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 6 }}>
      <svg width="34" height="34" viewBox="0 0 24 24" fill="none">
        <rect x="3" y="5" width="18" height="14" rx="2" stroke={brand.borderStrong} strokeWidth="1.4" />
        <path d="M3 10h18" stroke={brand.borderStrong} strokeWidth="1.4" />
        <path d="M8 14h8" stroke={brand.borderStrong} strokeWidth="1.4" strokeLinecap="round" />
      </svg>
      <div style={{ color: brand.textMuted, fontSize: font.caption }}>{text}</div>
      {hint ? <div style={{ color: brand.textMuted, fontSize: font.tiny }}>{hint}</div> : null}
      {action ? <div style={{ marginTop: 6 }}>{action}</div> : null}
    </div>
  );
}

/** 骨架行：加载中统一用它们。 */
export function SkeletonRows({ rows = 4, height = 16 }) {
  const items = [];
  for (let i = 0; i < rows; i++) {
    items.push(<div key={i} className="logan-skeleton" style={{ height, marginBottom: space.sm, width: (92 - i * 6) + "%" }} />);
  }
  return <div>{items}</div>;
}

/** 指标卡（对标 Bugly 头部三卡）：主数值 + 副指标 + 可选趋势线；pending 表示「待接入」。 */
export function MetricCard(props) {
  const { title, value, unit, tone = "primary", sub, footer, pending, onClick, active, spark } = props;
  const accent = tone === "danger" ? semantic.danger
    : tone === "warning" ? semantic.warning
      : tone === "success" ? semantic.success : brand.primary;
  const box = Object.assign({ cursor: onClick ? "pointer" : "default" }, cardStyle, {
    position: "relative",
    overflow: "hidden",
    display: "flex",
    alignItems: "center",
    padding: 0,
    borderColor: active ? accent : brand.border,
    boxShadow: active ? "0 0 0 2px " + accent + "22" : shadow.card
  });
  return (
    <div className="logan-metric" onClick={onClick || undefined} style={box}>
      <div className="logan-metric-bar" style={{ background: accent }} />
      <div className="logan-metric-body">
        <div style={{ fontSize: font.caption, color: brand.textSecondary }}>{title}</div>
        <div style={{ display: "flex", alignItems: "baseline", gap: 4, marginTop: 2 }}>
          <span style={{ fontSize: font.display, fontWeight: font.weightBold, color: pending ? brand.textMuted : brand.text, lineHeight: 1.15 }}>
            {pending ? "待接入" : (value === null || value === undefined ? "-" : value)}
          </span>
          {(unit && !pending) ? <span style={{ fontSize: font.h2, color: brand.textMuted }}>{unit}</span> : null}
        </div>
        {sub ? <div style={{ fontSize: font.caption, color: brand.textMuted, marginTop: 4 }}>{sub}</div> : null}
        {footer ? <div style={{ marginTop: space.sm }}>{footer}</div> : null}
      </div>
      {spark ? <div style={{ paddingRight: space.md }}>{spark}</div> : null}
    </div>
  );
}

/** 迷你趋势线（卡片内嵌）。 */
export function Sparkline({ values, color = brand.primary, width = 72, height = 28 }) {
  const arr = (values || []).map((v) => Number(v || 0));
  if (!arr.length) return null;
  const max = Math.max(1, Math.max.apply(null, arr));
  const step = arr.length > 1 ? width / (arr.length - 1) : width;
  const pts = arr.map((v, i) => (i * step).toFixed(1) + "," + (height - (v / max) * (height - 3) - 1.5).toFixed(1)).join(" ");
  return (
    <svg width={width} height={height}>
      <polyline points={pts} fill="none" stroke={color} strokeWidth="1.6" strokeLinejoin="round" />
    </svg>
  );
}

/** 横向排行榜（Bugly 式 Top5）：序号 + 标签 + 双指标 + 比例条。 */
export function RankBarList(props) {
  const {
    items = [], valueKey = "events", deviceKey = "devices", labelKey = "label",
    emptyText = "暂无数据", onPick, accent = brand.primary, unit = "次"
  } = props;
  if (!items.length) return <EmptyState height={96} text={emptyText} />;
  const max = Math.max.apply(null, items.map((it) => Number(it[valueKey] || 0)).concat([1]));
  return (
    <div>
      {items.map((it, idx) => {
        const v = Number(it[valueKey] || 0);
        const dv = Number(it[deviceKey] || 0);
        const pct = (v / max) * 100;
        return (
          <div key={String(it.groupKey || "") + idx} className="logan-rank-row"
            onClick={onPick ? () => onPick(it) : undefined}
            style={{ cursor: onPick ? "pointer" : "default" }}>
            <span className="logan-rank-idx" style={idx < 3 ? { background: accent, color: "#fff" } : undefined}>{idx + 1}</span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: "flex", justifyContent: "space-between", gap: space.md }}>
                <span className="logan-ellipsis" title={String(it[labelKey] || "")}>{it[labelKey] || "未知"}</span>
                <span style={{ color: brand.textSecondary, fontSize: font.caption, whiteSpace: "nowrap" }}>
                  <b style={{ color: brand.text }}>{fmtNum(v)}</b> {unit} · 影响 <b style={{ color: brand.text }}>{fmtNum(dv)}</b> 台
                </span>
              </div>
              <div className="logan-rank-track">
                <div className="logan-rank-fill" style={{ width: pct.toFixed(1) + "%", background: accent }} />
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

export { fmtNum, fmtRate };
