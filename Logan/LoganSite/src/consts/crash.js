// 崩溃维度常量：kind 与 LLM 严重性的展示映射（对齐 Bugly 的红色系警示语义）

export const KIND_META = {
  java: { label: "Java", color: "#2f54eb" },
  native: { label: "Native", color: "#fa541c" },
  anr: { label: "ANR", color: "#faad14" },
  nsexception: { label: "OC异常", color: "#eb2f96" },
  mach: { label: "Mach", color: "#a0d911" },
  jscrash: { label: "JS崩溃", color: "#13c2c2" },
  cppcrash: { label: "CPP崩溃", color: "#fa541c" },
  freeze: { label: "冻屏", color: "#faad14" },
  app: { label: "应用异常", color: "#bfbfbf" },
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


export const FIX_ACTION_META = {
  fixed: { text: '已修复', color: '#52c41a' },
  partial: { text: '部分修复', color: '#faad14' },
  verifying: { text: '验证中', color: '#1890ff' },
  verified: { text: '已验证', color: '#13c2c2' },
  wontfix: { text: '不修复', color: '#8c8c8c' },
  reopen: { text: '重新打开', color: '#f5222d' },
};

export const FIX_STATUS_META = {
  fixed: { text: '已修复', color: '#52c41a' },
  partial: { text: '部分修复', color: '#faad14' },
  verifying: { text: '验证中', color: '#1890ff' },
  verified: { text: '已验证', color: '#13c2c2' },
  wontfix: { text: '不修复', color: '#8c8c8c' },
  open: { text: '未处理', color: '#f5222d' },
};

export const SOURCE_META = {
  auto_client: { text: '客户端自动登记', color: 'blue' },
  auto_crash: { text: '崩溃上报自动登记', color: 'geekblue' },
  manual: { text: '人工登记', color: 'green' },
};
