import React, { Component } from "react";
import { connect } from "react-redux";
import { parse } from "qs";
import moment from "moment";
import {
  Card, Row, Col, Table, Tag, Select, Input, Button, Icon, Tooltip, Badge, message, Modal,
  DatePicker, Tabs,
} from "antd";
import {
  fetchCrashIssuesApi, fetchCrashStatsApi, fetchCrashTrendApi, updateCrashIssueStatusApi,
  fetchCrashSearchApi, fetchCrashIssueSearchApi, fetchCrashFilterValuesApi, fetchSingleCrashApi,
  reportIssueOpApi, batchIssueOpApi,
} from "../../common/api";
import { KIND_META, SEVERITY_META, STATUS_META, parseJsonArrayMaybe } from "../../consts/crash";
import TrendChart from "./components/TrendChart";
import { DisposeModal, BatchDisposeModal, rememberedOperator } from "../../common/components/ui/dispose";
import { exportCsv, ColumnPicker, filterHidden } from "../../common/components/ui/exportCsv";
import { getCurrentAppId, ensureApp, subscribe as subscribeApp } from "../../common/currentApp";

const { Option } = Select;
const { confirm } = Modal;
const { RangePicker } = DatePicker;
const { TabPane } = Tabs;

const EMPTY_FILTERS = {
  kind: "",
  statusFilter: "",
  brand: "",
  model: "",
  deviceId: "",
  appVersion: "",
  range: null,
  keyword: "",
};

// 导出 CSV 的列（与页面展示解耦：导出要的是原始值，不要 render 出来的彩色标签）
const ISSUE_CSV_COLUMNS = [
  { title: "问题ID", dataIndex: "id" },
  { title: "严重性", dataIndex: "llmSeverity" },
  { title: "类型", dataIndex: "kind" },
  { title: "信号/异常", dataIndex: "signalInfo" },
  { title: "AI 摘要", dataIndex: "llmSummary" },
  { title: "指纹", dataIndex: "fingerprint" },
  { title: "发生次数", dataIndex: "crashCount" },
  { title: "影响设备", dataIndex: "affectedDevices", csvText: (r) => { const s = String(r.affectedDevices || "").trim(); if (!s) return ""; try { const a = JSON.parse(s); return Array.isArray(a) ? a.join(" ") : s; } catch (e) { return s; } } },
  { title: "状态", dataIndex: "status", csvText: (r) => (STATUS_META[r.status] ? STATUS_META[r.status].label : r.status) },
  { title: "处理人", dataIndex: "assignee" },
  { title: "重开次数", dataIndex: "reopenCount" },
  { title: "首次发生", dataIndex: "firstCrashTime", csvText: (r) => (r.firstCrashTime ? moment(Number(r.firstCrashTime)).format("YYYY-MM-DD HH:mm:ss") : "") },
  { title: "最近发生", dataIndex: "lastCrashTime", csvText: (r) => (r.lastCrashTime ? moment(Number(r.lastCrashTime)).format("YYYY-MM-DD HH:mm:ss") : "") },
  { title: "修复结论", dataIndex: "lastFixNote" },
];

const CRASH_CSV_COLUMNS = [
  { title: "崩溃ID", dataIndex: "id" },
  { title: "发生时间", dataIndex: "crashTime", csvText: (r) => (r.crashTime ? moment(Number(r.crashTime)).format("YYYY-MM-DD HH:mm:ss") : "") },
  { title: "类型", dataIndex: "kind" },
  { title: "平台", dataIndex: "platform", csvText: (r) => (Number(r.platform) === 1 ? "安卓" : Number(r.platform) === 2 ? "iOS" : Number(r.platform) === 3 ? "鸿蒙" : (r.platform == null ? "" : r.platform)) },
  { title: "设备码", dataIndex: "deviceId" },
  { title: "品牌", dataIndex: "brand" },
  { title: "机型", dataIndex: "model" },
  { title: "系统版本", dataIndex: "releaseVer" },
  { title: "应用版本", dataIndex: "appVersion" },
  { title: "构建号", dataIndex: "buildVersion" },
  { title: "ABI", dataIndex: "abi" },
  { title: "用户", dataIndex: "userId" },
  { title: "信号/异常", dataIndex: "signalInfo" },
  { title: "顶部堆栈", dataIndex: "topFrames", csvText: (r) => { const s = r.topFrames == null ? "" : String(r.topFrames); try { const a = JSON.parse(s); if (Array.isArray(a)) return a.join(" | "); } catch (e) {} return s; } },
  { title: "来源", dataIndex: "deferred", csvText: (r) => (r.deferred ? "补传" : "实时") },
  { title: "指纹", dataIndex: "fingerprint" },
];

/** 把筛选条件转成后端参数（时间转毫秒时间戳）。 */
function toParams(filters, extra) {
  const p = Object.assign({}, extra || {});
  const put = (k, v) => {
    if (v !== undefined && v !== null && v !== "") p[k] = v;
  };
  put("kind", filters.kind);
  put("brand", filters.brand);
  put("model", filters.model);
  put("deviceId", filters.deviceId);
  put("appVersion", filters.appVersion);
  if (filters.statusFilter !== "" && filters.statusFilter !== undefined && filters.statusFilter !== null) {
    p.status = Number(filters.statusFilter);
  }
  if (filters.range && filters.range.length === 2 && filters.range[0] && filters.range[1]) {
    p.beginTime = filters.range[0].valueOf();
    p.endTime = filters.range[1].valueOf();
  }
  put("keyword", filters.keyword);
  return p;
}

/**
 * 崩溃概览（仿 Bugly 控制台）：KPI 卡片 + 按天趋势 + 综合搜索（机型/设备码/时间…）
 * + 聚合问题 / 崩溃明细 双 Tab。数据全部来自 Server 的 /logan/crash/* 查询 API。
 */
export class CrashList extends Component {

  state = {
    appId: "",
    filters: Object.assign({}, EMPTY_FILTERS),
    applied: Object.assign({}, EMPTY_FILTERS),
    tab: "issues",
    issues: [],
    issueTotal: 0,
    crashes: [],
    crashTotal: 0,
    stats: null,
    trend: [],
    versionOptions: [],
    deviceOptions: [],
    loading: false,
    crashLoading: false,
    activeContent: null,
    activeTitle: "",
    disposeTarget: null,
    selectedIssueIds: [],
    hiddenIssueCols: [],
    hiddenCrashCols: [],
    batchVisible: false,
  };

  componentDidMount() {
    let params = {};
    if (this.props.location) {
      params = parse(this.props.location.search, { ignoreQueryPrefix: true });
    }
    const appId = params.appId || getCurrentAppId();
    const filters = Object.assign({}, EMPTY_FILTERS);
    // 支持 URL 带入筛选条件，便于外部深链
    ["kind", "brand", "model", "deviceId", "appVersion", "keyword"].forEach((k) => {
      if (params[k]) filters[k] = String(params[k]);
    });
    if (params.status !== undefined && params.status !== "") filters.statusFilter = String(params.status);
    this.setState({ appId, filters, applied: Object.assign({}, filters) }, () => {
      this.loadStatsAndTrend();
      this.loadIssuesByServer();
      this.loadCrashes();
      this.loadFilterValues();
    });
    // 顶栏切换应用：URL 没指定 appId 时跟随全局应用重载
    ensureApp().then(() => {
      if (!params.appId) {
        const cur = getCurrentAppId();
        if (cur !== this.state.appId) {
          this.setState({ appId: cur }, () => this.reloadAll());
        }
      }
    });
    this._unsubApp = subscribeApp((cur) => {
      if (!params.appId && cur !== this.state.appId) {
        this.setState({ appId: cur }, () => this.reloadAll());
      }
    });
  }

  componentWillUnmount() {
    if (this._unsubApp) { this._unsubApp(); }
  }

  /** 按当前已生效的筛选条件导出证据包（多维筛选，交给平台拼成 zip-ready JSON，本地 DSH 分析用） */
  /** 导出当前页列表为 CSV（纯前端，Excel 直接打开不乱码）。 */
  exportIssuesCsv = () => {
    const n = exportCsv(ISSUE_CSV_COLUMNS, this.state.issues, "崩溃聚合问题_" + (this.state.appId || "") + "_" + moment().format("YYYYMMDD_HHmm"));
    if (!n) { message.warning("当前没有可导出的数据"); return; }
    message.success("已导出 " + n + " 条聚合问题");
  };

  exportCrashesCsv = () => {
    const n = exportCsv(CRASH_CSV_COLUMNS, this.state.crashes, "崩溃明细_" + (this.state.appId || "") + "_" + moment().format("YYYYMMDD_HHmm"));
    if (!n) { message.warning("当前没有可导出的数据"); return; }
    message.success("已导出 " + n + " 条崩溃上报");
  };

  exportBundle = () => {
    const { appId, applied } = this.state;
    if (!appId) { message.warning("请先选择应用"); return; }
    const base = (window.API_BASE_URL || "/logan-web") + "/logan/crash/export.download";
    const q = Object.assign({ appId, maxIssues: 20 }, toParams(applied, {}));
    delete q.limit; delete q.offset;
    const qs = Object.keys(q).map((k) => encodeURIComponent(k) + "=" + encodeURIComponent(q[k])).join("&");
    window.open(base + "?" + qs, "_blank");
  };

  reloadAll = () => {
    this.loadStatsAndTrend();
    this.loadIssuesByServer();
    this.loadCrashes();
    this.loadFilterValues();
  };

  loadFilterValues = async () => {
    const { appId } = this.state;
    try {
      const data = await fetchCrashFilterValuesApi(appId);
      if (data) {
        this.setState({
          versionOptions: data.appVersions || [],
          deviceOptions: data.devices || [],
        });
      }
    } catch (e) {
      // 下拉候选取不到不影响主流程
    }
  };

  /** KPI 固定看全量（概览语义），趋势跟随筛选的时间范围。
   *  注意：issues/issueTotal 一律由 loadIssuesByServer 负责，这里不碰，避免竞态覆盖。 */
  loadStatsAndTrend = async () => {
    const { appId, applied } = this.state;
    try {
      const t = toParams(applied);
      const [stats, trend] = await Promise.all([
        fetchCrashStatsApi(appId),
        fetchCrashTrendApi(appId, t.beginTime || null, t.endTime || null),
      ]);
      this.setState({ stats: stats || null, trend: trend || [] });
    } catch (e) {
      // 拦截器已弹错误提示
    }
  };

  /** 聚合问题：走服务端综合搜索（机型/设备码/时间/关键词等条件在 SQL 层过滤）。 */
  loadIssuesByServer = async () => {
    const { appId, applied } = this.state;
    this.setState({ loading: true });
    try {
      const data = await fetchCrashIssueSearchApi(toParams(applied, { appId: this.state.appId, offset: 0, limit: 100 }));
      this.setState({
        issues: (data && data.list) || [],
        issueTotal: (data && data.total) || 0,
      });
    } catch (e) {
      // 拦截器已提示
    } finally {
      this.setState({ loading: false });
    }
  };

  /** 崩溃明细：服务端分页直查。 */
  loadCrashes = async (offset = 0, pageSize = 15) => {
    const { appId, applied } = this.state;
    this.setState({ crashLoading: true });
    try {
      const data = await fetchCrashSearchApi(toParams(applied, { appId: appId, offset: offset, limit: pageSize }));
      this.setState({
        crashes: (data && data.list) || [],
        crashTotal: (data && data.total) || 0,
      });
    } catch (e) {
      // 拦截器已提示
    } finally {
      this.setState({ crashLoading: false });
    }
  };

  applySearch = () => {
    this.setState({ applied: Object.assign({}, this.state.filters) }, () => {
      this.loadStatsAndTrend();
      this.loadIssuesByServer();
      this.loadCrashes();
    });
  };

  resetSearch = () => {
    this.setState({ filters: Object.assign({}, EMPTY_FILTERS), applied: Object.assign({}, EMPTY_FILTERS) }, () => {
      this.loadStatsAndTrend();
      this.loadIssuesByServer();
      this.loadCrashes();
    });
  };

  setFilter = (key, value) => {
    this.setState({ filters: Object.assign({}, this.state.filters, { [key]: value }) });
  };

  /** 任一输入框回车即查询，符合后台系统习惯。 */
  onFilterKeyDown = (e) => {
    if (e.key === "Enter") this.applySearch();
  };

  openDetail = (record) => {
    window.location.hash = "#/crash-detail?id=" + record.id + "&appId=" + encodeURIComponent(this.state.appId);
  };

  // 快捷改状态：走 /logan/issue/op.json 留痕。没记住操作人时先弹处置弹窗补填，避免无痕改状态。
  changeStatus = (record, status) => {
    const operator = rememberedOperator();
    if (!operator) {
      message.warning("请先在处置弹窗填写操作人（平台无账号体系，状态变更需要留痕到人）");
      this.setState({ disposeTarget: record });
      return;
    }
    confirm({
      title: "将问题标记为「" + STATUS_META[status].label + "」？",
      content: "指纹 " + record.fingerprint + "，操作人 " + operator + "",
      onOk: async () => {
        await reportIssueOpApi({
          targetType: "crash_issue", targetId: record.id, appId: this.state.appId,
          op: "status", status: status, operator: operator,
        });
        message.success("已更新状态并记录留痕");
        this.loadIssuesByServer();
      },
    });
  };

  showStack = async (record) => {
    this.setState({ activeContent: "", activeTitle: "崩溃实例 #" + record.id + " · " + record.deviceId });
    try {
      const full = await fetchSingleCrashApi(record.id);
      this.setState({ activeContent: (full && full.content) || record.content || "（无堆栈正文）" });
    } catch (e) {
      this.setState({ activeContent: record.content || "（读取失败）" });
    }
  };

  /** 明细行 → 同指纹聚合问题：指纹填进关键词并切到聚合 Tab。 */
  gotoSameIssue = (record) => {
    const filters = Object.assign({}, this.state.filters, { keyword: record.fingerprint, kind: record.kind, model: "", deviceId: "", brand: "", range: null });
    this.setState({ filters, applied: Object.assign({}, filters), tab: "issues" }, () => {
      this.loadIssuesByServer();
    });
  };

  severityCell = (sev) => {
    if (!sev) return <Tag>待分析</Tag>;
    const meta = SEVERITY_META[sev] || { color: "#999", bg: "#fafafa" };
    return (
      <span style={{ color: meta.color, background: meta.bg, padding: "1px 8px", borderRadius: 3, fontSize: 12, fontWeight: 600 }}>
        {sev}
      </span>
    );
  };

  renderKpi() {
    const { stats } = this.state;
    const kinds = (stats && stats.kinds) || [];
    const card = (title, value, sub, color, icon) => (
      <Card bodyStyle={{ padding: "16px 20px" }} style={styles.kpiCard}>
        <div style={{ display: "flex", alignItems: "center" }}>
          <div style={{ ...styles.kpiIcon, background: color }}>
            <Icon type={icon} style={{ color: "#fff", fontSize: 22 }} />
          </div>
          <div style={{ marginLeft: 14 }}>
            <div style={{ color: "#8c8c8c", fontSize: 13 }}>{title}</div>
            <div style={{ fontSize: 26, fontWeight: 700, lineHeight: "32px", color: "#262626" }}>{value}</div>
            {sub && <div style={{ fontSize: 12, color: "#bfbfbf" }}>{sub}</div>}
          </div>
        </div>
      </Card>
    );
    const kindTotal = (k) => {
      const row = kinds.find((r) => r.kind === k);
      return row ? row.total : 0;
    };
    return (
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>{card("崩溃上报总数", (stats && stats.total) || 0, undefined, "#f5222d", "bug")}</Col>
        <Col span={6}>{card("Java 崩溃", kindTotal("java"), undefined, "#2f54eb", "code")}</Col>
        <Col span={6}>{card("Native 崩溃", kindTotal("native"), undefined, "#fa541c", "tool")}</Col>
        <Col span={6}>{card("ANR", kindTotal("anr"), "未处理问题 " + ((stats && stats.unresolvedIssues) || 0), "#faad14", "clock-circle")}</Col>
      </Row>
    );
  }

  renderFilterBar() {
    const { filters, deviceOptions, versionOptions } = this.state;
    const brands = [];
    (deviceOptions || []).forEach((d) => {
      if (d.brand && brands.indexOf(d.brand) < 0) brands.push(d.brand);
    });
    const models = [];
    (deviceOptions || []).forEach((d) => {
      if (d.model && models.indexOf(d.model) < 0) models.push(d.model);
    });
    return (
      <Card bodyStyle={{ padding: "14px 16px" }} style={{ marginBottom: 16, borderRadius: 8 }}>
        <Row gutter={[10, 10]} type="flex" align="middle" style={{ flexWrap: "wrap" }}>
          <Col>
            <Select style={{ width: 110 }} value={filters.kind} onChange={(v) => this.setFilter("kind", v)}>
              <Option value="">全部类型</Option>
              <Option value="java">Java</Option>
              <Option value="native">Native</Option>
              <Option value="anr">ANR</Option>
            </Select>
          </Col>
          <Col>
            <Select style={{ width: 110 }} value={filters.statusFilter} onChange={(v) => this.setFilter("statusFilter", v)}>
              <Option value="">全部状态</Option>
              <Option value="0">未处理</Option>
              <Option value="1">处理中</Option>
              <Option value="2">已修复</Option>
              <Option value="3">忽略</Option>
            </Select>
          </Col>
          <Col>
            <Select
              style={{ width: 110 }}
              value={filters.brand || undefined}
              placeholder="品牌"
              allowClear
              showSearch
              onChange={(v) => this.setFilter("brand", v || "")}
            >
              {brands.map((b) => <Option key={b} value={b}>{b}</Option>)}
            </Select>
          </Col>
          <Col>
            <Select
              style={{ width: 140 }}
              value={filters.model || undefined}
              placeholder="机型"
              allowClear
              showSearch
              onChange={(v) => this.setFilter("model", v || "")}
            >
              {models.map((m) => <Option key={m} value={m}>{m}</Option>)}
            </Select>
          </Col>
          <Col>
            <Input
              style={{ width: 170 }}
              placeholder="设备码 deviceId"
              prefix={<Icon type="hdd" style={{ color: "#bfbfbf" }} />}
              allowClear
              value={filters.deviceId}
              onChange={(e) => this.setFilter("deviceId", e.target.value)}
              onKeyDown={this.onFilterKeyDown}
            />
          </Col>
          <Col>
            <Select
              style={{ width: 150 }}
              value={filters.appVersion || undefined}
              placeholder="版本"
              allowClear
              showSearch
              onChange={(v) => this.setFilter("appVersion", v || "")}
            >
              {versionOptions.map((v) => <Option key={v} value={v}>{v}</Option>)}
            </Select>
          </Col>
          <Col>
            <RangePicker
              showTime={{ format: "HH:mm" }}
              format="YYYY-MM-DD HH:mm"
              placeholder={["开始时间", "结束时间"]}
              value={filters.range}
              onChange={(dates) => this.setFilter("range", dates)}
              style={{ width: 320 }}
            />
          </Col>
          <Col>
            <Input
              placeholder="关键词 堆栈/信号/指纹"
              style={{ width: 190 }}
              allowClear
              value={filters.keyword}
              onChange={(e) => this.setFilter("keyword", e.target.value)}
              onPressEnter={this.applySearch}
            />
          </Col>
          <Col>
            <Button type="primary" icon="search" onClick={this.applySearch}>查询</Button>
            <Button style={{ marginLeft: 8 }} icon="redo" onClick={this.resetSearch}>重置</Button>
          </Col>
        </Row>
        {(filters.range && filters.range.length === 2) || filters.model || filters.brand || filters.deviceId || filters.appVersion || filters.keyword || filters.kind || filters.statusFilter ? (
          <div style={{ marginTop: 10, color: "#8c8c8c", fontSize: 12 }}>
            <Icon type="filter" style={{ marginRight: 6 }} />
            已应用筛选：聚合问题表中的「次数/影响设备」按筛选范围重算；时间范围同时作用于趋势图。
          </div>
        ) : null}
      </Card>
    );
  }

  issueColumns = [
    {
      title: "严重性", dataIndex: "llmSeverity", width: 84,
      render: this.severityCell,
    },
    {
      title: "崩溃摘要", dataIndex: "llmSummary", ellipsis: true,
      render: (text, record) => (
        <div>
          <div
            style={{ color: "#1890ff", cursor: "pointer", fontWeight: 500 }}
            onClick={() => this.openDetail(record)}
          >
            {record.signalInfo || record.fingerprint}
          </div>
          <div style={{ color: "#8c8c8c", fontSize: 12, marginTop: 2, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: 520 }}>
            {text || "LLM 分析中，稍后自动生成…"}
          </div>
        </div>
      ),
    },
    {
      title: "类型", dataIndex: "kind", width: 84,
      render: (k) => {
        const meta = KIND_META[k] || { label: k, color: "#999" };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: "次数", dataIndex: "filteredCrashCount", width: 80,
      render: (v, r) => {
        const n = v == null ? r.crashCount : v;
        const filtered = v != null && v !== r.crashCount;
        return (
          <span style={{ fontWeight: 600, color: n > 0 ? "#f5222d" : "#262626" }}>
            {n}
            {filtered && <span style={{ fontSize: 11, color: "#bfbfbf", fontWeight: 400 }}> /{r.crashCount}</span>}
          </span>
        );
      },
    },
    {
      title: "影响设备", dataIndex: "affectedDevices", width: 92,
      render: (v, r) => {
        const devs = parseJsonArrayMaybe(v);
        const n = r.filteredDevices == null ? devs.length : r.filteredDevices;
        return n ? (
          <Tooltip title={devs.length ? devs.join("\n") : undefined}>
            <span>{n} 台</span>
          </Tooltip>
        ) : "-";
      },
    },
    {
      title: "首次 / 最近发生", dataIndex: "lastCrashTime", width: 170,
      render: (v, r) => (
        <span style={{ fontSize: 12, color: "#595959" }}>
          {r.firstCrashTime ? moment(r.firstCrashTime).format("MM-DD HH:mm") : "-"}
          {"  /  "}
          <b>{v ? moment(v).format("MM-DD HH:mm") : "-"}</b>
        </span>
      ),
    },
    {
      title: "处理人", dataIndex: "assignee", width: 96,
      render: (v, r) => (v
        ? <Tooltip title={r.assignTime ? "指派于 " + moment(Number(r.assignTime)).format("YYYY-MM-DD HH:mm") : undefined}>
            <span style={{ fontSize: 12, color: "#1d4ed8" }}>{v}</span>
          </Tooltip>
        : <span style={{ color: "#bfbfbf", fontSize: 12 }}>未指派</span>),
    },
    {
      title: "状态", dataIndex: "status", width: 90,
      render: (s, record) => (
        <Select
          size="small"
          value={s}
          style={{ width: 84 }}
          onChange={(v) => this.changeStatus(record, v)}
        >
          {Object.keys(STATUS_META).map((k) => (
            <Option key={k} value={Number(k)}>{STATUS_META[k].label}</Option>
          ))}
        </Select>
      ),
    },
    {
      title: "操作", width: 80,
      render: (v, record) => (
        <span>
          <Button size="small" type="primary" ghost onClick={() => this.openDetail(record)}>详情</Button>
          <Button size="small" style={{ marginLeft: 6 }} onClick={() => this.setState({ disposeTarget: record })}>处置</Button>
        </span>
      ),
    },
  ];

  crashColumns = [
    {
      title: "时间", dataIndex: "crashTime", width: 150,
      render: (v) => <span style={{ fontSize: 12 }}>{v ? moment(v).format("YYYY-MM-DD HH:mm:ss") : "-"}</span>,
    },
    {
      title: "类型", dataIndex: "kind", width: 76,
      render: (k) => {
        const meta = KIND_META[k] || { label: k, color: "#999" };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: "设备码", dataIndex: "deviceId", width: 150,
      render: (v) => <span style={{ fontFamily: "monospace", fontSize: 12 }}>{v || "-"}</span>,
    },
    {
      title: "品牌 / 机型", width: 140,
      render: (v, r) => <span style={{ fontSize: 12 }}>{[r.brand, r.model].filter(Boolean).join(" ") || "-"}</span>,
    },
    {
      title: "版本", width: 110,
      render: (v, r) => <span style={{ fontSize: 12 }}>{[r.appVersion, r.buildVersion ? "(" + r.buildVersion + ")" : ""].filter(Boolean).join(" ") || "-"}</span>,
    },
    {
      title: "信号 / 异常", dataIndex: "signalInfo", ellipsis: true,
      render: (v, r) => (
        <span style={{ fontSize: 12, color: "#595959" }}>{v || (r.topFrames ? String(r.topFrames).slice(0, 90) : r.fingerprint)}</span>
      ),
    },
    {
      title: "来源", dataIndex: "deferred", width: 70,
      render: (v) => (v ? <Badge status="warning" text="补传" /> : <Badge status="success" text="实时" />),
    },
    {
      title: "操作", width: 160,
      render: (v, record) => (
        <span>
          <Button size="small" onClick={() => this.showStack(record)}>堆栈</Button>
          <Button size="small" type="primary" ghost style={{ marginLeft: 6 }} onClick={() => this.gotoSameIssue(record)}>同类问题</Button>
        </span>
      ),
    },
  ];

  render() {
    const { loading, crashLoading, appId, tab, issues, crashes, crashTotal, issueTotal, activeContent, activeTitle } = this.state;
    return (
      <div style={{ padding: 24, overflowY: "auto", height: "100%", background: "#f5f6f8" }}>
        <div style={{ display: "flex", alignItems: "center", marginBottom: 16 }}>
          <span style={{ fontSize: 20, fontWeight: 700, color: "#262626" }}>崩溃分析</span>
          <span style={{ marginLeft: 12, color: "#8c8c8c", fontSize: 13 }}>appId: {appId || "-"}</span>
          <Button icon="download" style={{ marginLeft: "auto", marginRight: 8 }} onClick={this.exportBundle}>导出证据包</Button>
          <Button icon="reload" onClick={this.applySearch} loading={loading || crashLoading}>刷新</Button>
        </div>

        {this.renderKpi()}

        {this.renderFilterBar()}

        <Card title="近 14 天趋势" bodyStyle={{ padding: "12px 16px" }} style={{ marginBottom: 16 }}>
          <TrendChart data={this.state.trend} />
        </Card>

        <Card bodyStyle={{ padding: "0 16px 16px" }} style={{ borderRadius: 8 }}>
          <Tabs activeKey={tab} onChange={(k) => this.setState({ tab: k })}>
            <TabPane tab={"聚合问题 (" + issueTotal + ")"} key="issues">
              <div style={{ padding: "8px 0 4px" }}>
                <Button size="small" icon="check-square" disabled={!this.state.selectedIssueIds.length}
                  onClick={() => this.setState({ batchVisible: true })}>批量处置{this.state.selectedIssueIds.length ? "（" + this.state.selectedIssueIds.length + "）" : ""}</Button>
                <span style={{ marginLeft: 10, color: "#8c8c8c", fontSize: 12 }}>勾选后可统一改状态并逐条留痕</span>
                <span style={{ float: "right" }}>
                  <Button size="small" icon="file-text" onClick={this.exportIssuesCsv}>导出 CSV</Button>
                  <ColumnPicker
                    columns={this.issueColumns}
                    storageKey="logan_cols_crashlist_issues"
                    hidden={this.state.hiddenIssueCols}
                    onChange={(h) => this.setState({ hiddenIssueCols: h })}
                  />
                </span>
              </div>
              <Table
                rowKey="id"
                size="middle"
                loading={loading}
                dataSource={issues}
                columns={filterHidden(this.issueColumns, this.state.hiddenIssueCols)}
                rowSelection={{ selectedRowKeys: this.state.selectedIssueIds, onChange: (keys) => this.setState({ selectedIssueIds: keys }) }}
                pagination={{ pageSize: 15, showTotal: (t) => "共 " + t + " 个问题" }}
              />
            </TabPane>
            <TabPane tab={"崩溃明细 (" + crashTotal + ")"} key="crashes">
              <div style={{ padding: "8px 0 4px", textAlign: "right" }}>
                <Button size="small" icon="file-text" onClick={this.exportCrashesCsv}>导出 CSV</Button>
                <ColumnPicker
                  columns={this.crashColumns}
                  storageKey="logan_cols_crashlist_crashes"
                  hidden={this.state.hiddenCrashCols}
                  onChange={(h) => this.setState({ hiddenCrashCols: h })}
                />
              </div>
              <Table
                rowKey="id"
                size="middle"
                loading={crashLoading}
                dataSource={crashes}
                columns={filterHidden(this.crashColumns, this.state.hiddenCrashCols)}
                pagination={{
                  pageSize: 15,
                  total: crashTotal,
                  showTotal: (t) => "共 " + t + " 条崩溃上报",
                  onChange: (page, pageSize) => this.loadCrashes((page - 1) * pageSize, pageSize),
                }}
              />
            </TabPane>
          </Tabs>
        </Card>

        <Modal
          title={activeTitle}
          visible={activeContent !== null}
          footer={<Button onClick={() => this.setState({ activeContent: null })}>关闭</Button>}
          onCancel={() => this.setState({ activeContent: null })}
          width={900}
        >
          <pre style={styles.stack}>{this.state.activeContent || "加载中…"}</pre>
        </Modal>

        <DisposeModal
          visible={!!this.state.disposeTarget}
          target={this.state.disposeTarget}
          targetType="crash_issue"
          appId={appId}
          title={this.state.disposeTarget ? ("问题处置 · #" + this.state.disposeTarget.id) : "问题处置"}
          onClose={() => this.setState({ disposeTarget: null })}
          onDone={() => this.loadIssuesByServer()}
        />

        <BatchDisposeModal
          visible={this.state.batchVisible}
          ids={this.state.selectedIssueIds}
          targetType="crash_issue"
          appId={appId}
          onClose={() => this.setState({ batchVisible: false })}
          onDone={() => this.setState({ batchVisible: false, selectedIssueIds: [] }, () => this.loadIssuesByServer())}
        />
      </div>
    );
  }
}

const styles = {
  kpiCard: { borderRadius: 8 },
  kpiIcon: { width: 48, height: 48, borderRadius: 8, display: "flex", alignItems: "center", justifyContent: "center" },
  stack: {
    background: "#1e1e1e", color: "#d4d4d4", padding: 16, borderRadius: 6, maxHeight: 460,
    overflow: "auto", fontSize: 12, lineHeight: 1.6, whiteSpace: "pre-wrap", wordBreak: "break-all",
  },
};

export default connect()(CrashList);
