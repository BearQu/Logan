// 网络监控常量：kind / 错误类别 / 处置状态 的展示映射，与 Server 的 HttpNorm 取值域一一对应。

export const HTTP_KIND_META = {
  native: { label: "原生请求", color: "#2f54eb" },
  h5: { label: "H5 请求", color: "#722ed1" },
  app: { label: "应用异常", color: "#bfbfbf" },
};

export const ERRKIND_META = {
  cancel: { label: "取消", color: "#8c8c8c" },
  timeout: { label: "超时", color: "#fa541c" },
  ssl: { label: "证书", color: "#f5222d" },
  parse: { label: "解析", color: "#faad14" },
  connect: { label: "连接失败", color: "#f5222d" },
  other: { label: "其他", color: "#13c2c2" },
};

export const HTTP_STATUS_META = {
  0: { label: "未处理", color: "red" },
  1: { label: "处理中", color: "blue" },
  2: { label: "已修复", color: "green" },
};

export const SLOW_HINT_MS = 3000;

export function statusColor(status) {
  if (!status) { return "#8c8c8c"; }
  if (status >= 500) { return "#f5222d"; }
  if (status >= 400) { return "#fa8c16"; }
  return "#52c41a";
}