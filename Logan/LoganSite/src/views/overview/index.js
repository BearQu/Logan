import React, { Component } from "react";
import { connect } from "react-redux";
import { parse, stringify } from "qs";
import moment from "moment";
import { Select, Button, DatePicker, Icon, Table, Tooltip, Badge } from "antd";
import {
  fetchOverviewSummaryApi, fetchOverviewFiltersApi, fetchAlertRulesApi, fetchAlertRecordsApi,
} from "../../common/api";
import { getCurrentAppId, ensureApp, subscribe as subscribeApp } from "../../common/currentApp";
import { brand, kindColor, space, font, fmtNum, fmtRate, fmtAgo } from "../../common/theme";
import {
  Panel, MetricCard, EmptyState, KindTag, PlatformTag, StatusTag, DeltaTag, Tag, RankBarList,
} from "../../common/components/ui/kit";
import { BarTrendChart, DonutChart, Legend } from "../../common/components/ui/charts";

const { Option } = Select;
const { RangePicker } = DatePicker;

const QUICK_RANGES = [
  { key: "today", label: "今天", span: [moment().startOf("day"), moment()] },
  { key: "yesterday", label: "昨天", span: [moment().subtract(1, "day").startOf("day"), moment().subtract(1, "day").endOf("day")] },
  { key: "7d", label: "近 7 天", span: [moment().subtract(7, "day").startOf("day"), moment()] },
  { key: "30d", label: "近 30 天", span: [moment().subtract(30, "day").startOf("day"), moment()] },
];

/** LLM 严重性（中文两行协议产物）→ 语义标签；没有摘要时明确显示「待摘要」而不是空白。 */
function LlmSeverityTag({ value }) {
  const v = String(value || "");
  if (!v) return <Tag>待摘要</Tag>;
  let tone = "default";
  if (v === "致命" || v === "严重") tone = "danger";
  else if (v === "警告") tone = "warning";
  else if (v === "一般") tone = "info";
  return <Tag tone={tone}>{v}</Tag>;
}

/**
 * 异常概览（对标 Bugly「异常概览」首页；所有数字来自 /logan/overview/summary.json）。
 * 三率指标卡（崩溃率 / ANR 率 / 错误率，分母 = 窗口内联网设备数）→ 崩溃趋势（小时/天，可对照版本）
 * → 类型构成环图 → Top 问题（含 AI 初判、环比波动、重开次数）→ 系统/机型/版本 Top5 榜。
 * 口径全部由服务端计算；后端返回 null 的一律显示「-」或「待接入」，绝不显示假的 0。
 */
export class Overview extends Component {

  state = {
    appId: "",
    range: QUICK_RANGES[2].span,
    granularity: "",
    platform: "",
    appVersion: "",
    compareVersion: "",
    versionOptions: [],
    data: null,
    loading: true,
    quickKey: QUICK_RANGES[2].key,
    alertToday: null,
    alertEnabled: null,
  };

  componentDidMount() {
    const q = this.props.location ? parse(this.props.location.search, { ignoreQueryPrefix: true }) : {};
    const appId = q.appId || getCurrentAppId();
    const st = { appId };
    if (q.beginTime && q.endTime) {
      st.range = [moment(Number(q.beginTime)), moment(Number(q.endTime))];
      st.quickKey = "";
    }
    if (q.granularity) st.granularity = String(q.granularity);
    if (q.platform) st.platform = String(q.platform);
    this.setState(st, this.reload);
    ensureApp().then(() => {
      if (!q.appId) {
        const cur = getCurrentAppId();
        if (cur !== this.state.appId) this.setState({ appId: cur }, this.reload);
      }
    });
    this._unsub = subscribeApp((cur) => {
      if (!q.appId && cur !== this.state.appId) this.setState({ appId: cur }, this.reload);
    });
  }

  componentWillUnmount() {
    if (this._unsub) this._unsub();
  }

  loadAlertBadge = () => {
    const appId = this.state.appId;
    if (!appId) { return; }
    const dayBegin = moment().startOf("day").valueOf();
    fetchAlertRulesApi({ appId: appId, limit: 200 })
      .then((res) => {
        const d = res || {};
        this.setState({ alertEnabled: (typeof d.enabledTotal === "number") ? d.enabledTotal : ((d.rules || []).filter((r) => Number(r.enabled) === 1).length) });
      })
      .catch(() => {});
    fetchAlertRecordsApi({ appId: appId, limit: 300 })
      .then((rows) => {
        const list = Array.isArray(rows) ? rows : [];
        this.setState({ alertToday: list.filter((x) => Number(x.addTime) >= dayBegin).length });
      })
      .catch(() => {});
  };

  reload = () => {
    const { appId, range, granularity, platform, appVersion, compareVersion } = this.state;
    if (!appId) {
      this.setState({ loading: false, data: null });
      return;
    }
    this.setState({ loading: true });
    this.loadAlertBadge();
    const params = { appId };
    if (range && range[0] && range[1]) {
      params.beginTime = range[0].valueOf();
      params.endTime = range[1].valueOf();
    }
    if (granularity) params.granularity = granularity;
    if (platform) params.platform = Number(platform);
    if (appVersion) params.appVersion = appVersion;
    if (compareVersion) params.compareVersion = compareVersion;
    fetchOverviewSummaryApi(params)
      .then((d) => this.setState({ data: d, loading: false }))
      .catch(() => this.setState({ loading: false }));
    fetchOverviewFiltersApi({ appId })
      .then((f) => this.setState({ versionOptions: (f && f.appVersions) || [] }))
      .catch(() => {});
  };

  gotoCrash = (query) => {
    const { appId } = this.state;
    window.location.hash = "#/crash-list?" + stringify(Object.assign({ appId }, query || {}));
  };

  gotoHttp = (query) => {
    const { appId } = this.state;
    window.location.hash = "#/http-list?" + stringify(Object.assign({ appId }, query || {}));
  };

  gotoDetail = (row) => {
    const { appId } = this.state;
    window.location.hash = "#/crash-detail?" + stringify({ id: row.issueId, appId });
  };

  renderCards() {
    const { data, loading } = this.state;
    if (loading) {
      const cells = [];
      for (let i = 0; i < 5; i++) {
        cells.push(<div key={i} className="logan-skeleton" style={{ height: 104 }} />);
      }
      return <div className="logan-metric-grid">{cells}</div>;
    }
    if (!data || !data.cards) {
      return <EmptyState text="暂无统计数据" hint="请先在左上角选择应用，或放宽时间范围" />;
    }
    const c = data.cards;
    const crash = c.crash || null;
    const anr = c.anr || null;
    const err = c.error || null;
    return (
      <div className="logan-metric-grid">
        <MetricCard
          title="联网设备数"
          value={fmtNum(c.onlineDevices)}
          unit="台"
          sub="窗口内在任一上报通道出现过的去重设备（口径同 Bugly 联网设备数）"
        />
        <MetricCard
          title="设备崩溃率"
          value={crash ? fmtRate(crash.deviceRate) : "-"}
          tone="danger"
          active
          onClick={() => this.gotoCrash({})}
          sub={crash ? (fmtNum(crash.events) + " 次崩溃 · 影响 " + fmtNum(crash.affectedDevices) + " 台 · " + fmtNum(crash.issues) + " 个问题") : "-"}
        />
        <MetricCard
          title="ANR 率"
          value={anr ? fmtRate(anr.deviceRate) : "-"}
          tone="warning"
          onClick={() => this.gotoCrash({ kind: "anr" })}
          sub={anr ? (fmtNum(anr.events) + " 次 · 影响 " + fmtNum(anr.affectedDevices) + " 台 · " + fmtNum(anr.issues) + " 个问题") : "-"}
        />
        <MetricCard
          title="错误率（非致命）"
          pending={!err || !err.available}
          value={err && err.available ? fmtRate(err.deviceRate) : "-"}
          tone="info"
          sub={err && err.note ? err.note : (err ? (fmtNum(err.events) + " 次 · 影响 " + fmtNum(err.affectedDevices) + " 台") : "待接入")}
        />
        <MetricCard
          title="失败请求"
          value={fmtNum(c.httpFailed)}
          unit="次"
          tone="success"
          onClick={() => this.gotoHttp({ statusFlag: "failed" })}
          sub={"慢请求 " + fmtNum(c.httpSlow) + " 次（超过阈值仍未返回）"}
        />
      </div>
    );
  }

  renderTrend() {
    const { data, loading } = this.state;
    const t = data ? data.trend : null;
    const buckets = ((t && t.buckets) || []).map((b) => ({
      t: b.bucketTime, events: b.events, devices: b.devices,
    }));
    return (
      <Panel
        title="崩溃趋势"
        hint={t ? ("粒度 " + (t.granularity === "hour" ? "按小时" : "按天") + "，窗口合计 " + fmtNum(t.totalEvents) + " 个事件；柱子悬停可看每桶事件数与影响设备数") : undefined}
        loading={loading}
        extra={<Legend items={[{ label: "崩溃事件", color: brand.primary }]} />}
      >
        <BarTrendChart buckets={buckets} granularity={t ? t.granularity : "hour"} />
      </Panel>
    );
  }

  renderKinds() {
    const { data, loading } = this.state;
    const kinds = (data && data.kinds) || [];
    const items = kinds.map((k) => ({
      label: String(k.kind || "other") + " · " + fmtNum(k.events) + " 次 / " + fmtNum(k.devices) + " 台 / " + fmtNum(k.issues) + " 问题",
      value: Number(k.events || 0),
      color: kindColor[String(k.kind || "").toLowerCase()] || kindColor.other,
    }));
    return (
      <Panel title="崩溃类型构成" hint="环图按事件数占比；图例含影响设备数与问题数" loading={loading}>
        {items.length ? <DonutChart data={items} centerLabel="崩溃次数" /> : <EmptyState text="窗口内没有崩溃" />}
      </Panel>
    );
  }

  renderTopIssues() {
    const { data, loading } = this.state;
    const rows = (data && data.topIssues) || [];
    const cols = [
      { title: "严重性", width: 78, render: (r) => <LlmSeverityTag value={r.llmSeverity} /> },
      {
        title: "类型", width: 92,
        render: (r) => (
          <span>
            <KindTag kind={r.kind} />
            <div style={{ marginTop: 2 }}><PlatformTag platform={r.platform} /></div>
          </span>
        ),
      },
      {
        title: "问题（AI 初判）",
        render: (r) => (
          <div>
            <a className="logan-ellipsis" style={{ fontWeight: font.weightBold, color: brand.text, display: "block" }}
              onClick={() => this.gotoDetail(r)}>
              {r.signalInfo || r.fingerprint || ("问题 #" + r.issueId)}
            </a>
            <div className="logan-ellipsis" style={{ color: brand.textSecondary, fontSize: font.caption, marginTop: 2 }}>
              {r.llmSummary || "AI 摘要生成中…"}
            </div>
            {Number(r.reopenCount) > 0 ? <span style={{ display: "inline-block", marginTop: 4 }}><Tag tone="warning">已重开 {Number(r.reopenCount)} 次</Tag></span> : null}
          </div>
        ),
      },
      { title: "次数", width: 72, render: (r) => <b>{fmtNum(r.windowEvents)}</b> },
      { title: "影响设备", width: 88, render: (r) => fmtNum(r.windowDevices) + " 台" },
      { title: "环比", width: 94, render: (r) => <DeltaTag value={r.delta} isNew={r.isNew} /> },
      { title: "状态", width: 86, render: (r) => <StatusTag status={r.status} /> },
      {
        title: "最近发生", width: 112,
        render: (r) => (
          <span style={{ color: brand.textMuted, fontSize: font.caption }}>{fmtAgo(r.lastWindowTime || r.lastCrashTime)}</span>
        ),
      },
    ];
    return (
      <Panel
        title="Top 问题"
        hint="按窗口内发生次数排序；环比对照前一等长窗口（取不到显示 -），首次出现标「新增」"
        loading={loading}
      >
        <Table rowKey={(r) => String(r.issueId)} dataSource={rows} columns={cols} size="middle"
          loading={false} pagination={false}
          locale={{ emptyText: <EmptyState text="窗口内没有崩溃问题" /> }} />
      </Panel>
    );
  }

  renderRankPanel = (title, items, onPick) => (
    <Panel title={title} hint="点击任意一行跳转崩溃列表并带上该筛选" loading={this.state.loading} style={{ flex: "1 1 300px", minWidth: 280 }}>
      <RankBarList items={items || []} onPick={onPick} />
    </Panel>
  );

  renderRanking() {
    const rk = (this.state.data && this.state.data.ranking) || {};
    return (
      <div style={{ display: "flex", gap: space.lg, flexWrap: "wrap" }}>
        {this.renderRankPanel("系统版本 Top5", rk.os, () => undefined)}
        {this.renderRankPanel("机型 Top5", rk.device, (it) => {
          const parts = String(it.groupKey || "").split("|");
          this.gotoCrash({ brand: parts[0] || "", model: parts[1] || "" });
        })}
        {this.renderRankPanel("应用版本 Top5", rk.version, (it) => this.gotoCrash({ appVersion: it.value || it.label }))}
      </div>
    );
  }

  renderToolbar() {
    const { range, granularity, platform, appVersion, compareVersion, versionOptions, quickKey } = this.state;
    return (
      <div className="logan-toolbar">
        <div className="logan-toolbar-row">
          <span className="logan-toolbar-label">时间</span>
          <Button.Group>
            {QUICK_RANGES.map((r) => (
              <Button key={r.key} type={quickKey === r.key ? "primary" : "default"} size="small"
                onClick={() => this.setState({ range: r.span, quickKey: r.key }, this.reload)}>
                {r.label}
              </Button>
            ))}
          </Button.Group>
          <RangePicker showTime size="small" format="YYYY-MM-DD HH:mm" value={range }
            onChange={(v) => this.setState({ range: v, quickKey: "" }, this.reload)} />
          <span className="logan-toolbar-label">粒度</span>
          <Select size="small" style={{ width: 96 }} value={granularity}
            onChange={(v) => this.setState({ granularity: v }, this.reload)}>
            <Option value="">自动</Option>
            <Option value="hour">按小时</Option>
            <Option value="day">按天</Option>
          </Select>
        </div>
        <div className="logan-toolbar-row">
          <span className="logan-toolbar-label">平台</span>
          <Select size="small" style={{ width: 120 }} value={platform}
            onChange={(v) => this.setState({ platform: v }, this.reload)}>
            <Option value="">全部端</Option>
            <Option value="1">Android</Option>
            <Option value="2">iOS</Option>
            <Option value="3">HarmonyOS</Option>
          </Select>
          <span className="logan-toolbar-label">版本</span>
          <Select size="small" style={{ width: 156 }} value={appVersion} showSearch allowClear
            placeholder="全部版本" onChange={(v) => this.setState({ appVersion: v || "" }, this.reload)}>
            {versionOptions.map((v) => <Option key={v} value={v}>{v}</Option>)}
          </Select>
          <span className="logan-toolbar-label">对照版本</span>
          <Select size="small" style={{ width: 156 }} value={compareVersion} showSearch allowClear
            placeholder="不对照" onChange={(v) => this.setState({ compareVersion: v || "" }, this.reload)}>
            {versionOptions.filter((v) => v !== appVersion).map((v) => <Option key={v} value={v}>{v}</Option>)}
          </Select>
          <Button size="small" icon="reload" onClick={this.reload}>刷新</Button>
          <Tooltip title="设备崩溃率 = 窗口内发生崩溃的去重设备数 / 窗口内联网设备数；ANR 率同理。分母为 0 时显示「-」而不是 0%。">
            <span style={{ color: brand.textMuted, fontSize: font.caption, cursor: "help" }}>
              <Icon type="info-circle" /> 口径说明
            </span>
          </Tooltip>
        </div>
      </div>
    );
  }

  render() {
    return (
      <div className="logan-overview">
        <header className="logan-page-head">
          <h2 className="logan-page-title">异常概览</h2>
          <span className="logan-page-sub">崩溃 / ANR / 非致命错误 / 网络一屏总览，数字全部来自端上真实上报</span>
          <a
            href="#/alert"
            style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 6 }}
            title="报警规则与触发记录"
          >
            <Icon type="notification" style={{ color: "#d97706" }} />
            <Badge count={this.state.alertToday || 0} overflowCount={99} style={{ backgroundColor: "#dc2626" }}>
              <span style={{ color: "#111827", fontSize: 13 }}>今日报警</span>
            </Badge>
            <span style={{ color: "#8c8c8c", fontSize: 12 }}>
              启用规则 {this.state.alertEnabled == null ? "-" : this.state.alertEnabled}
            </span>
          </a>
        </header>
        {this.renderToolbar()}
        {this.renderCards()}
        <div style={{ marginTop: space.lg }}>{this.renderTrend()}</div>
        <div style={{ marginTop: space.lg }}>{this.renderKinds()}</div>
        <div style={{ marginTop: space.lg }}>{this.renderTopIssues()}</div>
        <div style={{ marginTop: space.lg }}>{this.renderRanking()}</div>
      </div>
    );
  }
}

function mapStateToProps(state) {
  return { location: state.router.location };
}

export default connect(mapStateToProps)(Overview);
