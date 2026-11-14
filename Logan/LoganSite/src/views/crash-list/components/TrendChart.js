import React from "react";
import moment from "moment";

/** 零依赖 SVG 按天分组柱状图：data=[{d,kind,cnt}]。Bugly 式按类型着色。 */
export default function TrendChart({ data, height = 180 }) {
  const kinds = ["java", "native", "anr"];
  const kindColor = { java: "#2f54eb", native: "#fa541c", anr: "#faad14" };
  const days = [];
  const map = {};
  const today = moment().startOf("day");
  for (let i = 13; i >= 0; i--) {
    const d = moment(today).subtract(i, "days").format("YYYY-MM-DD");
    days.push(d);
    map[d] = { java: 0, native: 0, anr: 0 };
  }
  (data || []).forEach(row => {
    const d = String(row.d);
    if (map[d] && kinds.indexOf(row.kind) >= 0) map[d][row.kind] += Number(row.cnt || 0);
  });
  const max = Math.max(1, ...days.flatMap(d => kinds.map(k => map[d][k])));
  const W = Math.max(560, days.length * 44);
  const pad = { l: 34, r: 10, t: 12, b: 24 };
  const plotH = height - pad.t - pad.b;
  const bw = (W - pad.l - pad.r) / days.length;
  const bars = [];
  days.forEach((d, di) => {
    let x = pad.l + di * bw + bw * 0.18;
    const usable = bw * 0.64;
    const step = usable / kinds.length;
    kinds.forEach((k, ki) => {
      const v = map[d][k];
      const h = v === 0 ? 0 : Math.max(2, (v / max) * plotH);
      bars.push(
        <g key={d + k}>
          {h > 0 && (
            <rect x={x + ki * step} y={pad.t + plotH - h} width={step - 2} height={h} fill={kindColor[k]} rx={2}>
              <title>{`${d} ${k}: ${v}`}</title>
            </rect>
          )}
          {v > 0 && (
            <text x={x + ki * step + (step - 2) / 2} y={pad.t + plotH - h - 3} fontSize={10} textAnchor="middle" fill="#666">{v}</text>
          )}
        </g>
      );
    });
    bars.push(
      <text key={d + "lab"} x={pad.l + di * bw + bw / 2} y={height - 8} fontSize={10} textAnchor="middle" fill="#999">
        {moment(d).format("MM-DD")}
      </text>
    );
  });
  const gridYs = [0, 0.5, 1].map(p => pad.t + plotH - p * plotH);
  return (
    <div style={{ overflowX: "auto" }}>
      <svg width={W} height={height}>
        {gridYs.map((gy, i) => (
          <line key={i} x1={pad.l} x2={W - pad.r} y1={gy} y2={gy} stroke="#f0f0f0" />
        ))}
        <text x={4} y={pad.t + 4} fontSize={10} fill="#999">{max}</text>
        <text x={4} y={pad.t + plotH + 4} fontSize={10} fill="#999">0</text>
        {bars}
      </svg>
      <div style={{ paddingLeft: pad.l, marginTop: 4 }}>
        {kinds.map(k => (
          <span key={k} style={{ marginRight: 16, fontSize: 12, color: "#666" }}>
            <span style={{ display: "inline-block", width: 10, height: 10, background: kindColor[k], borderRadius: 2, marginRight: 4 }} />
            {k}
          </span>
        ))}
      </div>
    </div>
  );
}
