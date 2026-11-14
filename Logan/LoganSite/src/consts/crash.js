// 崩溃维度常量：kind 与 LLM 严重性的展示映射（对齐 Bugly 的红色系警示语义）

export const KIND_META = {
  java: { label: "Java", color: "#2f54eb" },
  native: { label: "Native", color: "#fa541c" },
  anr: { label: "ANR", color: "#faad14" },
};

export const SEVERITY_META = {
  "致命": { color: "#f5222d", bg: "#fff1f0" },
  "严重": { color: "#fa541c", bg: "#fff2e8" },
  "警告": { color: "#faad14", bg: "#fffbe6" },
  "一般": { color: "#1890ff", bg: "#e6f7ff" },
};

export const STATUS_META = {
  0: { label: "未处理", color: "red" },
  1: { label: "处理中", color: "blue" },
  2: { label: "已修复", color: "green" },
  3: { label: "忽略", color: "default" },
};

export function parseJsonArrayMaybe(s) {
  if (!s) return [];
  try {
    const v = JSON.parse(s);
    return Array.isArray(v) ? v : [];
  } catch (e) {
    return [];
  }
}
