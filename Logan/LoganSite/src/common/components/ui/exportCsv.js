// 列表导出与列自定义：平台侧通用能力，任何表格页都能复用。
// CSV 带 UTF-8 BOM，Excel 打开中文不乱码；单元格里的引号/逗号/换行按 RFC4180 转义。
import React from "react";
import { Popover, Checkbox, Button, Icon } from "antd";

function cellText(v) {
  if (v === null || v === undefined) return "";
  if (typeof v === "object") {
    try { return JSON.stringify(v); } catch (e) { return String(v); }
  }
  return String(v);
}

function csvCell(v) {
  const s = cellText(v);
  if (/[",\r\n]/.test(s)) return '"' + s.replace(/"/g, '""') + '"';
  return s;
}

/**
 * 导出 CSV 并触发下载。
 * columns: antd 风格列定义（用 title/dataIndex，可带 csvText(row) 自定义取值）
 * rows: 数据行
 * filename: 文件名（不含扩展名会自动补 .csv）
 */
export function exportCsv(columns, rows, filename) {
  const cols = (columns || []).filter(function (c) {
    return c && c.title && (c.dataIndex || c.csvText || c.key) && c.hidden !== true;
  });
  if (!cols.length) return 0;
  const head = cols.map(function (c) { return csvCell(cellText(c.title)); }).join(",");
  const body = (rows || []).map(function (row) {
    return cols.map(function (c) {
      let v;
      if (typeof c.csvText === "function") {
        try { v = c.csvText(row); } catch (e) { v = ""; }
      } else {
        v = row ? row[c.dataIndex] : "";
      }
      return csvCell(v);
    }).join(",");
  });
  const text = '' + [head].concat(body).join("\r\n");
  const name = (filename || "export") + ".csv";
  const blob = new Blob([text], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
  return (rows || []).length;
}

function readHidden(storageKey) {
  if (!storageKey) return [];
  try {
    const raw = window.localStorage.getItem(storageKey);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch (e) {
    return [];
  }
}

/**
 * 列自定义：按 storageKey 记住用户隐藏的列（平台无账号体系，存本机即可）。
 * 返回 { columns, control }，control 直接渲染到工具条上。
 */
export class ColumnPicker extends React.Component {
  state = { hidden: [] };

  componentDidMount() {
    this.setState({ hidden: readHidden(this.props.storageKey) });
  }

  componentDidUpdate(prev) {
    if (prev.storageKey !== this.props.storageKey) {
      this.setState({ hidden: readHidden(this.props.storageKey) });
    }
  }

  toggle = (key, checked) => {
    const cur = this.state.hidden.slice();
    const at = cur.indexOf(key);
    if (checked && at >= 0) cur.splice(at, 1);
    if (!checked && at < 0) cur.push(key);
    this.setState({ hidden: cur }, () => {
      if (this.props.storageKey) {
        try { window.localStorage.setItem(this.props.storageKey, JSON.stringify(cur)); } catch (e) {}
      }
      if (this.props.onChange) this.props.onChange(cur);
    });
  };

  columnKey = (c, idx) => String(c.key !== undefined && c.key !== null ? c.key : (c.dataIndex || "col-" + idx));

  render() {
    const { columns, storageKey } = this.props;
    if (!columns || !columns.length) return null;
    const hid = this.state.hidden;
    const visible = columns.filter((c, i) => hid.indexOf(this.columnKey(c, i)) < 0);
    const content = (
      <div style={{ maxWidth: 260, maxHeight: 300, overflowY: "auto" }}>
        {columns.map((c, i) => {
          const k = this.columnKey(c, i);
          if (!c.title) return null;
          return (
            <div key={k} style={{ padding: "3px 0" }}>
              <Checkbox
                checked={hid.indexOf(k) < 0}
                onChange={(e) => this.toggle(k, e.target.checked)}
              >
                <span style={{ fontSize: 12 }}>{cellText(c.title)}</span>
              </Checkbox>
            </div>
          );
        })}
        {storageKey ? (
          <div style={{ marginTop: 6, borderTop: "1px solid #e5e9f0", paddingTop: 6 }}>
            <a style={{ fontSize: 12 }} onClick={() => this.setState({ hidden: [] }, () => {
              try { window.localStorage.removeItem(storageKey); } catch (e) {}
              if (this.props.onChange) this.props.onChange([]);
            })}>恢复全部列</a>
          </div>
        ) : null}
      </div>
    );
    return (
      <span>
        <Popover content={content} title="显示列" trigger="click" placement="bottomRight">
          <Button size="small" style={{ marginLeft: 8 }}>
            <Icon type="eye-o" /> 列
          </Button>
        </Popover>
        <span style={{ display: "none" }}>{visible.length}</span>
      </span>
    );
  }
}

/** 按 hidden 列表过滤列，供页面自己控制渲染（比 ColumnPicker 内部过滤更直观）。 */
export function filterHidden(columns, hidden) {
  const hid = hidden || [];
  if (!hid.length) return columns || [];
  return (columns || []).filter((c, i) => {
    const k = String(c && c.key !== undefined && c.key !== null ? c.key : (c && c.dataIndex ? c.dataIndex : "col-" + i));
    return hid.indexOf(k) < 0;
  });
}
