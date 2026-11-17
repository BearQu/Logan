import React from "react";
import { brand, font, space } from "../../theme";

/**
 * 手写 SVG 图表（项目零图表依赖：package.json 无 echarts/bizcharts，禁止为此新增依赖）。
 * 所有图表都必须真实反映数据：缺数据留空，不插值、不补假点。
 */

function bucketOf(b) { return b.bucketTime !== undefined ? b.bucketTime : b.t; }

const padOf = (withAxis) => (withAxis ? { l: 44, r: 12, t: 14, b: 26 } : { l: 4, r: 4, t: 4, b: 4 });

function fmtBucket(ts, granularity) {
  const d = new Date(Number(ts));
  const p = (n) => (n < 10 ? "0" + n : "" + n);
  if (granularity === "hour") {
    return p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":00";
  }
  return p(d.getMonth() + 1) + "-" + p(d.getDate());
}

/** 柱状趋势：buckets=[{t,events,devices}]；compare 为对照版本（半透明柱）。 */
export function BarTrendChart(props) {
  const {
    buckets = [], compare = null, granularity = "hour", height = 220,
    color = brand.primary, compareColor = "#94a3b8", unit = "次"
  } = props;
  if (!buckets.length) {
    return <div style={{ color: brand.textMuted, fontSize: font.caption, padding: space.lg }}>暂无趋势数据</div>;
  }
  const W = Math.max(680, buckets.length * (granularity === "hour" ? 26 : 46));
  const pad = padOf(true);
  const plotH = height - pad.t - pad.b;
  const plotW = W - pad.l - pad.r;
  const max = Math.max(1, ...buckets.map((b) => Number(b.events || 0)), ...((compare || []).map((b) => Number(b.events || 0))));
  const bw = plotW / buckets.length;
  const labelEvery = Math.max(1, Math.ceil(buckets.length / (granularity === "hour" ? 12 : 14)));
  const nodes = [];
  buckets.forEach((b, i) => {
    const v = Number(b.events || 0);
    const h = v === 0 ? 0 : Math.max(2, (v / max) * plotH);
    const x = pad.l + i * bw;
    const hasCompare = compare && compare[i];
    if (hasCompare) {
      const cv = Number(compare[i].events || 0);
      const ch = cv === 0 ? 0 : Math.max(2, (cv / max) * plotH);
      nodes.push(
        <rect key={"c" + i} x={x + bw * 0.08} y={pad.t + plotH - ch} width={bw * 0.44} height={ch}
          fill={compareColor} opacity="0.32" rx="2">
          <title>{fmtBucket(bucketOf(b), granularity) + " 对照: " + cv + " " + unit}</title>
        </rect>
      );
    }
    nodes.push(
      <rect key={"b" + i} x={x + (hasCompare ? bw * 0.5 : bw * 0.18)} y={pad.t + plotH - h}
        width={bw * (hasCompare ? 0.44 : 0.64)} height={h} fill={color} rx="2">
        <title>{fmtBucket(b.t, granularity) + " 事件: " + v + " " + unit + " / 影响设备: " + Number(b.devices || 0)}</title>
      </rect>
    );
    if (i % labelEvery === 0) {
      nodes.push(
        <text key={"l" + i} x={x + bw / 2} y={height - 8} fontSize={10} textAnchor="middle" fill={brand.textMuted}>
          {fmtBucket(b.t, granularity)}
        </text>
      );
    }
  });
  const gridRows = [0, 0.25, 0.5, 0.75, 1].map((p) => (
    <g key={"g" + p}>
      <line x1={pad.l} x2={W - pad.r} y1={pad.t + plotH - p * plotH} y2={pad.t + plotH - p * plotH} stroke="#eef1f6" />
      <text x={6} y={pad.t + plotH - p * plotH + 4} fontSize={10} fill={brand.textMuted}>{Math.round(max * p)}</text>
    </g>
  ));
  return (
    <div style={{ overflowX: "auto" }}>
      <svg width={W} height={height}>
        {gridRows}
        {nodes}
      </svg>
      <div style={{ paddingLeft: pad.l, marginTop: 4 }}>
        <Legend items={[{ label: "当前版本/筛选", color }, ...(compare ? [{ label: "对照版本", color: compareColor }] : [])]} />
      </div>
    </div>
  );
}

/** 环图（类型构成）：data=[{label,value,color}]，中心显示总数。 */
export function DonutChart(props) {
  const { data = [], size = 150, thickness = 18, centerLabel = "总计" } = props;
  const rows = data.filter((d) => Number(d.value || 0) > 0);
  const total = rows.reduce((s, d) => s + Number(d.value || 0), 0);
  if (!total) {
    return <div style={{ color: brand.textMuted, fontSize: font.caption, padding: space.md }}>暂无构成数据</div>;
  }
  const r = (size - thickness) / 2;
  const c = size / 2;
  const circ = 2 * Math.PI * r;
  let acc = 0;
  const arcs = rows.map((d, i) => {
    const frac = Number(d.value) / total;
    const dash = frac * circ;
    const el = (
      <circle key={i} cx={c} cy={c} r={r} fill="none" stroke={d.color || brand.primary} strokeWidth={thickness}
        strokeDasharray={dash.toFixed(2) + " " + (circ - dash).toFixed(2)}
        strokeDashoffset={(-acc * circ).toFixed(2)} transform={"rotate(-90 " + c + " " + c + ")"}>
        <title>{d.label + ": " + d.value + "（" + (frac * 100).toFixed(1) + "%）"}</title>
      </circle>
    );
    acc += frac;
    return el;
  });
  return (
    <div style={{ display: "flex", alignItems: "center", gap: space.xl }}>
      <svg width={size} height={size}>
        <circle cx={c} cy={c} r={r} fill="none" stroke="#f1f5f9" strokeWidth={thickness} />
        {arcs}
        <text x={c} y={c - 2} fontSize="20" fontWeight="600" textAnchor="middle" fill={brand.text}>{total}</text>
        <text x={c} y={c + 16} fontSize="11" textAnchor="middle" fill={brand.textMuted}>{centerLabel}</text>
      </svg>
      <div style={{ flex: 1, minWidth: 0 }}>
        {rows.map((d, i) => (
          <div key={i} style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6, fontSize: font.caption }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: d.color || brand.primary, flexShrink: 0 }} />
            <span className="logan-ellipsis" style={{ flex: 1, color: brand.textSecondary }}>{d.label}</span>
            <b style={{ color: brand.text }}>{d.value}</b>
            <span style={{ color: brand.textMuted, width: 48, textAlign: "right" }}>
              {((Number(d.value) / total) * 100).toFixed(1)}%
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** 图例（全站共用一份样式）。 */
export function Legend({ items = [] }) {
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: space.lg }}>
      {items.map((it, i) => (
        <span key={i} style={{ fontSize: font.caption, color: brand.textSecondary, display: "inline-flex", alignItems: "center", gap: 4 }}>
          <span style={{ width: 10, height: 10, borderRadius: 2, background: it.color, display: "inline-block" }} />
          {it.label}
        </span>
      ))}
    </div>
  );
}
