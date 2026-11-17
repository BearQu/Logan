// 错误分析（非致命异常）常量：kind 取值域与后端 ErrorNorm.kindOf 白名单一一对应。
// 宁缺毋假 —— 白名单之外的 kind 一律落 other，不臆造分类。

export const ERROR_KIND_META = {
  business: { label: '业务异常', color: '#dc2626', hint: '业务代码主动捕获后上报的可恢复异常' },
  caught: { label: '已捕获异常', color: '#ea580c', hint: 'SDK 代为捕获、未导致进程退出的异常' },
  ui: { label: '界面异常', color: '#d97706', hint: '渲染、控件回调等界面层异常' },
  promise: { label: 'Promise 失败', color: '#7c3aed', hint: '未处理的 Promise rejection' },
  resource: { label: '资源加载失败', color: '#0891b2', hint: '静态资源、图片、脚本加载失败' },
  other: { label: '其他', color: '#64748b', hint: '未归类的非致命异常' },
};

export const ERROR_STATUS_META = {
  0: { label: '未处理', color: '#dc2626' },
  1: { label: '处理中', color: '#d97706' },
  2: { label: '已修复', color: '#059669' },
  3: { label: '已忽略', color: '#94a3b8' },
};

export function kindMetaOf(kind) {
  var k = String(kind || '').toLowerCase();
  return ERROR_KIND_META[k] || ERROR_KIND_META.other;
}

export function statusMetaOf(v) {
  var n = Number(v || 0);
  return ERROR_STATUS_META[n] || ERROR_STATUS_META[0];
}

// 与后端 LoganErrorServiceImpl 的判定一致：fatal=1 表示「本会崩溃但被 SDK 兜住」，
// 这类事件在列表里要显著标出，因为它说明线上真实存在崩溃风险。
export const FATAL_HINT = '该异常若不兜住会导致进程退出，务必优先处理';
