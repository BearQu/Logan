import React, { Component } from 'react';
import { connect } from 'react-redux';
import moment from 'moment';
import {
  Card, Row, Col, Table, Select, Input, Button, Icon, Tooltip, message, Modal,
  DatePicker, Tabs, Empty,
} from 'antd';
import {
  fetchErrorListApi, fetchErrorSummaryApi, fetchErrorIssuesApi, fetchErrorAroundApi,
  fetchErrorTopApi, reportIssueOpApi,
} from '../../common/api';
import { getCurrentAppId, ensureApp, subscribe as subscribeApp } from '../../common/currentApp';
import { ERROR_KIND_META, kindMetaOf, statusMetaOf, FATAL_HINT } from '../../consts/error';
import { DisposeModal, BatchDisposeModal, rememberedOperator } from '../../common/components/ui/dispose';
import { exportCsv, ColumnPicker, filterHidden } from '../../common/components/ui/exportCsv';
import { MetricCard, Tag, PlatformTag, EmptyState } from '../../common/components/ui/kit';
import { brand, semantic, space, font } from '../../common/theme';

const Option = Select.Option;
const RangePicker = DatePicker.RangePicker;
const TabPane = Tabs.TabPane;

const EMPTY_FILTERS = {
  kind: '',
  platform: '',
  appVersion: '',
  deviceId: '',
  keyword: '',
  statusFlag: '',
  range: null,
};

function timeParams(filters) {
  var p = {};
  if (filters.range && filters.range.length === 2 && filters.range[0] && filters.range[1]) {
    p.beginTime = filters.range[0].valueOf();
    p.endTime = filters.range[1].valueOf();
  }
  return p;
}

function cut(s, n) {
  var t = String(s == null ? '' : s);
  return t.length > n ? t.slice(0, n) + '…' : t;
}

function fmtTs(v) {
  return v ? moment(Number(v)).format('MM-DD HH:mm:ss') : '-';
}

function deviceCountOf(row) {
  var t = String((row && row.affectedDevices) || '');
  if (!t) { return 0; }
  var seen = {};
  var n = 0;
  var parts = t.split(',');
  for (var i = 0; i < parts.length; i++) {
    var d = parts[i].trim();
    if (d && !seen[d]) { seen[d] = true; n += 1; }
  }
  return n;
}

// 严重性标签：后端 AI 摘要回填的是中文档位，原样显示；取不到显示占位而不是编造。
function SevTag(props) {
  var v = String(props.severity || '').trim();
  if (!v) { return <span style={{ color: brand.textMuted, fontSize: font.caption }}>-</span>; }
  var c = semantic.warning;
  if (v.indexOf('致') >= 0 || v === 'critical' || v === 'high' || v === '严重') { c = semantic.danger; }
  if (v.indexOf('一') >= 0 || v.indexOf('低') >= 0 || v === 'low') { c = semantic.info; }
  return <Tag color={c} soft={c + '14'}>{v}</Tag>;
}

/**
 * 错误分析（非致命异常）：对标 Bugly「错误分析」。
 * 采集由三端 SDK 自动完成（默认开启，SDK 侧一行可关），上报走 /logan/error/report.json，
 * 服务端统一归一化指纹并聚合；标记已修复后同指纹再上报会自动重开并保留历史结论。
 * 处置（状态 / 处理人 / 备注）与崩溃、网络问题共用同一张留痕表 /logan/issue/op.json。
 */
class ErrorListView extends Component {
  state = {
    appId: '',
    filters: Object.assign({}, EMPTY_FILTERS),
    applied: Object.assign({}, EMPTY_FILTERS),
    tab: 'issues',
    summary: null,
    top: [],
    issues: [],
    events: [],
    loading: false,
    detail: null,
    detailEvents: [],
    detailLoading: false,
    disposeTarget: null,
    selectedIds: [],
    batchVisible: false,
    hiddenIssueCols: [],
    hiddenEventCols: [],
  };

  componentDidMount() {
    var self = this;
    ensureApp().then(function () {
      self.setState({ appId: getCurrentAppId() });
      self.loadAll();
    });
    this._unsub = subscribeApp(function (appId) {
      self.setState({ appId: appId });
      self.loadAll();
    });
  }

  componentWillUnmount() {
    if (this._unsub) { this._unsub(); }
  }

  setFilter = (key, value) => {
    var f = Object.assign({}, this.state.filters);
    f[key] = value;
    this.setState({ filters: f });
  };

  loadAll = () => {
    var self = this;
    var appId = this.state.appId;
    if (!appId) { return Promise.resolve(); }
    var tp = timeParams(this.state.applied);
    this.setState({ loading: true });
    return fetchErrorSummaryApi(Object.assign({ appId: appId }, tp))
      .then(function (s) { self.setState({ summary: s || null }); })
      .catch(function () { self.setState({ summary: null }); })
      .then(function () {
        return fetchErrorTopApi(Object.assign({ appId: appId, limit: 8 }, tp))
          .then(function (rows) { self.setState({ top: rows || [] }); })
          .catch(function () { self.setState({ top: [] }); });
      })
      .then(function () { return self.loadTab(); });
  };

  loadTab = () => {
    var self = this;
    var st = this.state;
    var appId = st.appId;
    if (!appId) { return Promise.resolve(); }
    var a = st.applied;
    var tp = timeParams(a);
    this.setState({ loading: true });
    if (st.tab === 'issues') {
      var p1 = Object.assign({ appId: appId, limit: 200 }, tp);
      if (a.kind) { p1.kind = a.kind; }
      if (a.platform) { p1.platform = Number(a.platform); }
      if (a.statusFlag !== '' && a.statusFlag !== null && a.statusFlag !== undefined) { p1.statusFlag = Number(a.statusFlag); }
      if (a.keyword) { p1.keyword = a.keyword; }
      return fetchErrorIssuesApi(p1)
        .then(function (res) { self.setState({ issues: (res && res.items) || [], events: [], loading: false }); })
        .catch(function () { self.setState({ issues: [], events: [], loading: false }); });
    }
    var p2 = Object.assign({ appId: appId, limit: 300 }, tp);
    if (a.kind) { p2.kind = a.kind; }
    if (a.platform) { p2.platform = Number(a.platform); }
    if (a.deviceId) { p2.deviceId = String(a.deviceId).trim(); }
    if (a.appVersion) { p2.appVersion = a.appVersion; }
    if (a.keyword) { p2.keyword = a.keyword; }
    return fetchErrorListApi(p2)
      .then(function (res) { self.setState({ events: (res && res.items) || [], issues: [], loading: false }); })
      .catch(function () { self.setState({ events: [], issues: [], loading: false }); });
  };

  apply = () => {
    this.setState({ applied: Object.assign({}, this.state.filters), selectedIds: [] });
    this.loadAll();
  };

  reset = () => {
    this.setState({ filters: Object.assign({}, EMPTY_FILTERS), applied: Object.assign({}, EMPTY_FILTERS), selectedIds: [] });
    this.loadAll();
  };

  switchTab = (key) => {
    this.setState({ tab: key, selectedIds: [] });
    this.loadTab();
  };

  openDetail = (row) => {
    var self = this;
    var appId = this.state.appId;
    this.setState({ detail: row, detailEvents: [], detailLoading: true });
    var deviceId = row.sampleDeviceId || row.deviceId;
    if (!appId || !deviceId) {
      this.setState({ detailEvents: [], detailLoading: false });
      return;
    }
    var anchor = Number(row.lastSeen || row.occurredAt || Date.now());
    fetchErrorAroundApi({
      appId: appId,
      deviceId: deviceId,
      beginTime: anchor - 5 * 60 * 1000,
      endTime: anchor + 60 * 1000,
      limit: 40,
    })
      .then(function (rows) { self.setState({ detailEvents: rows || [], detailLoading: false }); })
      .catch(function () { self.setState({ detailEvents: [], detailLoading: false }); });
  };

  // 单条状态变更走统一处置接口（留痕含操作人）；没记住操作人时先弹处置框。
  changeStatus = (row, status) => {
    var self = this;
    var appId = this.state.appId;
    var op = rememberedOperator();
    if (!op) {
      this.setState({ disposeTarget: row });
      return;
    }
    reportIssueOpApi({
      targetType: 'error_issue',
      targetId: Number(row.id),
      appId: appId,
      op: 'status',
      status: status,
      operator: op,
    }).then(function () {
      message.success('已更新处置状态');
      self.loadTab();
    }).catch(function () { });
  };

  exportIssuesCsv = () => {
    var cols = [
      { title: '问题ID', dataIndex: 'id' },
      { title: '错误名', dataIndex: 'name' },
      { title: '标题', dataIndex: 'title' },
      { title: '类型', dataIndex: 'kind', csvText: function (r) { return kindMetaOf(r.kind).label; } },
      { title: '平台', dataIndex: 'platform', csvText: function (r) { return String(r.platform) === '1' ? '安卓' : (String(r.platform) === '2' ? 'iOS' : (String(r.platform) === '3' ? '鸿蒙' : r.platform)); } },
      { title: '严重性(AI)', dataIndex: 'llmSeverity' },
      { title: 'AI摘要', dataIndex: 'llmSummary' },
      { title: '样本消息', dataIndex: 'sampleMessage' },
      { title: '次数', dataIndex: 'eventCount' },
      { title: '影响设备数', dataIndex: 'affectedDevices', csvText: function (r) { return deviceCountOf(r); } },
      { title: '处置状态', dataIndex: 'statusFlag', csvText: function (r) { return statusMetaOf(r.statusFlag).label; } },
      { title: '处理人', dataIndex: 'assignee' },
      { title: '重开次数', dataIndex: 'reopenCount' },
      { title: '首次发生', dataIndex: 'firstSeen', csvText: function (r) { return r.firstSeen ? moment(Number(r.firstSeen)).format('YYYY-MM-DD HH:mm:ss') : ''; } },
      { title: '最近发生', dataIndex: 'lastSeen', csvText: function (r) { return r.lastSeen ? moment(Number(r.lastSeen)).format('YYYY-MM-DD HH:mm:ss') : ''; } },
      { title: '处置结论', dataIndex: 'lastFixNote' },
      { title: '指纹', dataIndex: 'fingerprint' },
    ];
    var n = exportCsv(cols, this.state.issues, '非致命错误_' + (this.state.appId || '') + '_' + moment().format('YYYYMMDD_HHmm'));
    if (!n) { message.warning('当前没有可导出的数据'); return; }
    message.success('已导出 ' + n + ' 条错误问题');
  };

  exportEventsCsv = () => {
    var cols = [
      { title: '事件ID', dataIndex: 'id' },
      { title: '发生时间', dataIndex: 'occurredAt', csvText: function (r) { return r.occurredAt ? moment(Number(r.occurredAt)).format('YYYY-MM-DD HH:mm:ss') : ''; } },
      { title: '错误名', dataIndex: 'name' },
      { title: '类型', dataIndex: 'kind' },
      { title: '消息', dataIndex: 'message' },
      { title: '堆栈', dataIndex: 'stack' },
      { title: '页面地址', dataIndex: 'pageUrl' },
      { title: '平台', dataIndex: 'platform' },
      { title: '系统', dataIndex: 'os' },
      { title: '系统版本', dataIndex: 'osVersion' },
      { title: '品牌', dataIndex: 'brand' },
      { title: '机型', dataIndex: 'model' },
      { title: '网络类型', dataIndex: 'networkType' },
      { title: '应用版本', dataIndex: 'appVersion' },
      { title: '构建号', dataIndex: 'buildVersion' },
      { title: '是否致命', dataIndex: 'fatal', csvText: function (r) { return Number(r.fatal) === 1 ? '是' : '否'; } },
      { title: '设备', dataIndex: 'deviceId' },
      { title: '链路ID', dataIndex: 'traceId' },
      { title: '指纹', dataIndex: 'fingerprint' },
    ];
    var n = exportCsv(cols, this.state.events, '非致命错误明细_' + (this.state.appId || '') + '_' + moment().format('YYYYMMDD_HHmm'));
    if (!n) { message.warning('当前没有可导出的数据'); return; }
    message.success('已导出 ' + n + ' 条错误明细');
  };

  issueColumns = () => {
    var self = this;
    return [
      { title: '严重性', width: 78, render: function (r) { return <SevTag severity={r.llmSeverity} />; } },
      {
        title: '错误名 / 类型', width: 320, render: function (r) {
          var m = kindMetaOf(r.kind);
          return (
            <div>
              <Tooltip title={r.name || r.title}>
                <a style={{ fontWeight: 600 }} onClick={function () { self.openDetail(r); }}>{cut(r.name || r.title || '(未命名错误)', 90)}</a>
              </Tooltip>
              <div style={{ marginTop: 3 }}>
                <Tag color={m.color} soft={m.color + '14'}>{m.label}</Tag>
                <PlatformTag platform={r.platform} />
              </div>
            </div>
          );
        },
      },
      {
        title: 'AI 初判', width: 300, render: function (r) {
          if (!r.llmSummary) { return <span style={{ color: brand.textMuted, fontSize: font.caption }}>摘要生成中（模型每 60 秒批量处理）</span>; }
          return <Tooltip title={r.llmSummary}><div style={{ fontSize: font.caption, color: brand.textSecondary }}>{cut(r.llmSummary, 80)}</div></Tooltip>;
        },
      },
      {
        title: '次数', width: 76, sorter: function (a, b) { return Number(a.eventCount || 0) - Number(b.eventCount || 0); },
        render: function (r) { return <span style={{ fontFamily: font.mono }}>{Number(r.eventCount || 0)}</span>; },
      },
      {
        title: '影响设备', width: 92, render: function (r) {
          var n = deviceCountOf(r);
          if (!n) { return <span style={{ color: brand.textMuted }}>-</span>; }
          return <Tooltip title={'设备：' + String(r.affectedDevices || '')}><span style={{ fontFamily: font.mono }}>{n} 台</span></Tooltip>;
        },
      },
      {
        title: '处置状态', width: 110, render: function (r) {
          var m = statusMetaOf(r.statusFlag);
          return (
            <div>
              <Tag color={m.color} soft={m.color + '14'}>{m.label}</Tag>
              {Number(r.reopenCount) > 0 ? <Tag tone="warning">已重开 {Number(r.reopenCount)} 次</Tag> : null}
            </div>
          );
        },
      },
      {
        title: '处理人', width: 96, render: function (r) {
          if (!r.assignee) { return <span style={{ color: brand.textMuted, fontSize: font.caption }}>未指派</span>; }
          return <span style={{ color: brand.primary }}>{r.assignee}</span>;
        },
      },
      { title: '最近发生', width: 130, render: function (r) { return <span style={{ fontFamily: font.mono, fontSize: font.caption }}>{fmtTs(r.lastSeen)}</span>; } },
      {
        title: '操作', width: 150, render: function (r) {
          return (
            <span>
              <Button size="small" onClick={function () { self.openDetail(r); }}>明细</Button>
              <Button size="small" style={{ marginLeft: 6 }} onClick={function () { self.setState({ disposeTarget: r }); }}>处置</Button>
            </span>
          );
        },
      },
    ];
  };

  eventColumns = () => {
    var self = this;
    return [
      { title: '发生时间', width: 140, render: function (r) { return <span style={{ fontFamily: font.mono, fontSize: font.caption }}>{fmtTs(r.occurredAt)}</span>; } },
      {
        title: '错误名', width: 300, render: function (r) {
          return (
            <div>
              <a onClick={function () { self.openDetail(r); }}>{cut(r.name || '(未命名错误)', 80)}</a>
              <div style={{ fontSize: font.caption, color: brand.textMuted }}>{cut(r.message || '', 110)}</div>
            </div>
          );
        },
      },
      { title: '类型', width: 110, render: function (r) { var m = kindMetaOf(r.kind); return <Tag color={m.color} soft={m.color + '14'}>{m.label}</Tag>; } },
      {
        title: '致命', width: 76, render: function (r) {
          if (Number(r.fatal) !== 1) { return <span style={{ color: brand.textMuted }}>否</span>; }
          return <Tooltip title={FATAL_HINT}><Tag tone="danger">兜住</Tag></Tooltip>;
        },
      },
      { title: '平台', width: 76, render: function (r) { return <PlatformTag platform={r.platform} />; } },
      { title: '设备 / 机型', width: 190, render: function (r) { return <Tooltip title={r.deviceId}><span style={{ fontSize: font.caption }}>{cut(r.brand ? (r.brand + ' ' + r.model) : r.deviceId, 34)}</span></Tooltip>; } },
      { title: '版本', width: 120, render: function (r) { return <span style={{ fontFamily: font.mono, fontSize: font.caption }}>{cut((r.appVersion || '-') + '(' + (r.buildVersion || '-') + ')', 24)}</span>; } },
      { title: '页面', width: 190, render: function (r) { return <Tooltip title={r.pageUrl}><span style={{ fontSize: font.caption }}>{cut(r.pageUrl || '-', 42)}</span></Tooltip>; } },
      { title: '网络', width: 90, render: function (r) { return <span style={{ fontSize: font.caption }}>{r.networkType || '-'}</span>; } },
      {
        title: '操作', width: 90, render: function (r) {
          return <Button size="small" onClick={function () { self.openDetail(r); }}>明细</Button>;
        },
      },
    ];
  };

  renderKpi() {
    var st = this.state;
    var s = st.summary || {};
    var available = s.available !== false;
    return (
      <Row gutter={12} style={{ marginBottom: space.md }}>
        <Col span={6}>
          <MetricCard title="非致命错误次数" value={available ? Number(s.events || 0) : null} unit="次" tone="danger" pending={!available}
            sub={available ? '可捕获异常 / Promise 失败 / 资源加载失败' : String(s.note || '非致命错误采集未接入')} />
        </Col>
        <Col span={6}>
          <MetricCard title="影响设备数" value={available ? Number(s.devices || 0) : null} unit="台" tone="warning" pending={!available}
            sub={available ? '窗口内出现过错误的去重设备' : '-'} />
        </Col>
        <Col span={6}>
          <MetricCard title="错误问题数" value={available ? Number(s.issues || 0) : null} unit="个" tone="primary" pending={!available}
            sub={available ? '同指纹自动聚合为一个问题' : '-'} />
        </Col>
        <Col span={6}>
          <MetricCard title="采集状态" value={available ? '已接入' : null} tone={available ? 'success' : 'warning'} pending={!available}
            sub={available ? '三端 SDK 默认自动采集，SDK 侧一行可关' : String(s.note || '等待 SDK 上报')} />
        </Col>
      </Row>
    );
  }

  renderTop() {
    var st = this.state;
    if (!st.top || !st.top.length) { return null; }
    var max = 0;
    for (var i = 0; i < st.top.length; i++) { max = Math.max(max, Number(st.top[i].events || 0)); }
    return (
      <Card size="small" title={<span><Icon type="fire" style={{ color: semantic.danger, marginRight: 6 }} />高频错误 Top {st.top.length}</span>}
        style={{ marginBottom: space.md }} bodyStyle={{ padding: '8px 16px 12px' }}>
        {st.top.map(function (row) {
          var ev = Number(row.events || 0);
          var pct = max > 0 ? Math.max(2, Math.round(ev / max * 100)) : 0;
          return (
            <div key={String(row.name)} style={{ display: 'flex', alignItems: 'center', marginBottom: 4 }}>
              <span style={{ width: 260, fontSize: font.caption, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={String(row.name || '')}>
                {cut(row.name || '(未命名错误)', 60)}
              </span>
              <span style={{ flex: 1, height: 8, background: '#f1f5f9', borderRadius: 4, overflow: 'hidden' }}>
                <span style={{ display: 'block', height: 8, width: pct + '%', background: kindMetaOf(row.kind).color }} />
              </span>
              <span style={{ width: 120, textAlign: 'right', fontFamily: font.mono, fontSize: font.caption }}>
                {ev} 次 / {Number(row.devices || 0)} 台
              </span>
            </div>
          );
        })}
      </Card>
    );
  }

  renderFilters() {
    var st = this.state;
    var f = st.filters;
    var self = this;
    return (
      <Card size="small" style={{ marginBottom: space.md }} bodyStyle={{ padding: '10px 16px' }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center' }}>
          <span style={{ fontSize: font.caption, color: brand.textSecondary, marginRight: 6 }}>时间</span>
          <RangePicker showTime format="YYYY-MM-DD HH:mm" value={f.range}
            onChange={function (v) { self.setFilter('range', v); }} />
          <span style={{ fontSize: font.caption, color: brand.textSecondary, marginLeft: 10, marginRight: 6 }}>类型</span>
          <Select style={{ width: 150 }} value={f.kind} onChange={function (v) { self.setFilter('kind', v); }}>
            <Option value="">全部类型</Option>
            {Object.keys(ERROR_KIND_META).map(function (k) {
              return <Option key={k} value={k}>{ERROR_KIND_META[k].label}</Option>;
            })}
          </Select>
          <span style={{ fontSize: font.caption, color: brand.textSecondary, marginLeft: 10, marginRight: 6 }}>平台</span>
          <Select style={{ width: 110 }} value={f.platform} onChange={function (v) { self.setFilter('platform', v); }}>
            <Option value="">全部</Option>
            <Option value="1">安卓</Option>
            <Option value="2">iOS</Option>
            <Option value="3">鸿蒙</Option>
          </Select>
          {st.tab === 'events' ? (
            <span style={{ marginLeft: 10 }}>
              <span style={{ fontSize: font.caption, color: brand.textSecondary, marginRight: 6 }}>设备码</span>
              <Input style={{ width: 180 }} placeholder="设备码精确匹配" value={f.deviceId}
                onChange={function (e) { self.setFilter('deviceId', e.target.value); }} />
            </span>
          ) : (
            <span style={{ marginLeft: 10 }}>
              <span style={{ fontSize: font.caption, color: brand.textSecondary, marginRight: 6 }}>处置状态</span>
              <Select style={{ width: 110 }} value={f.statusFlag} onChange={function (v) { self.setFilter('statusFlag', v); }}>
                <Option value="">全部</Option>
                <Option value="0">未处理</Option>
                <Option value="1">处理中</Option>
                <Option value="2">已修复</Option>
                <Option value="3">已忽略</Option>
              </Select>
            </span>
          )}
          <Input style={{ width: 220, marginLeft: 10 }} placeholder="错误名 / 消息关键词" value={f.keyword}
            onChange={function (e) { self.setFilter('keyword', e.target.value); }}
            onPressEnter={this.apply} />
          <Button type="primary" icon="search" style={{ marginLeft: 10 }} onClick={this.apply}>查询</Button>
          <Button icon="reload" style={{ marginLeft: 8 }} onClick={this.reset}>重置</Button>
          <span style={{ marginLeft: 'auto', fontSize: font.caption, color: brand.textMuted }}>
            <Icon type="info-circle" style={{ marginRight: 4 }} />
            错误由 SDK 自动上报，服务端归一化指纹；标记已修复后同指纹再上报会自动重开并保留历史结论
          </span>
        </div>
      </Card>
    );
  }

  renderDetailBody() {
    var st = this.state;
    var d = st.detail;
    if (!d) { return null; }
    var stack = String(d.stack || d.sampleStack || '');
    var m = kindMetaOf(d.kind);
    return (
      <div>
        <Row gutter={12} style={{ marginBottom: 12 }}>
          <Col span={12}><div style={{ fontSize: font.caption, color: brand.textMuted }}>错误名</div><div style={{ fontWeight: 600 }}>{d.name || d.title || '-'}</div></Col>
          <Col span={12}>
            <div style={{ fontSize: font.caption, color: brand.textMuted }}>类型 / 平台 / 致命性</div>
            <div>
              <Tag color={m.color} soft={m.color + '14'}>{m.label}</Tag>
              <PlatformTag platform={d.platform} />
              {Number(d.fatal) === 1 ? <Tag tone="danger">兜住的致命异常</Tag> : <Tag>非致命</Tag>}
            </div>
          </Col>
        </Row>
        {(d.llmSummary || st.detailIssue && st.detailIssue.llmSummary) ? (
          <div style={{ background: '#f8fafc', border: '1px solid ' + brand.border, borderRadius: 6, padding: 10, marginBottom: 12 }}>
            <div style={{ fontSize: font.caption, color: brand.textMuted, marginBottom: 4 }}>AI 初判</div>
            <div style={{ whiteSpace: 'pre-wrap' }}>{d.llmSummary}</div>
          </div>
        ) : null}
        {(d.message || d.sampleMessage) ? (
          <div style={{ marginBottom: 12 }}>
            <div style={{ fontSize: font.caption, color: brand.textMuted }}>消息</div>
            <div style={{ whiteSpace: 'pre-wrap', fontFamily: font.mono, fontSize: font.caption }}>{d.message || d.sampleMessage}</div>
          </div>
        ) : null}
        {stack ? (
          <div style={{ marginBottom: 12 }}>
            <div style={{ fontSize: font.caption, color: brand.textMuted }}>堆栈</div>
            <pre style={{ background: '#0f172a', color: '#e2e8f0', padding: 10, borderRadius: 6, maxHeight: 300, overflow: 'auto', fontSize: font.tiny }}>{stack}</pre>
          </div>
        ) : null}
        <Row gutter={12} style={{ fontSize: font.caption, color: brand.textSecondary }}>
          <Col span={8}>设备：{d.deviceId || d.sampleDeviceId || '-'}</Col>
          <Col span={8}>机型：{d.brand ? (d.brand + ' ' + d.model) : '-'}</Col>
          <Col span={8}>系统：{(d.os || '-') + ' ' + (d.osVersion || '')}</Col>
        </Row>
        <Row gutter={12} style={{ fontSize: font.caption, color: brand.textSecondary, marginTop: 4 }}>
          <Col span={8}>版本：{(d.appVersion || '-') + '(' + (d.buildVersion || '-') + ')'}</Col>
          <Col span={8}>网络：{d.networkType || '-'}</Col>
          <Col span={8}>链路：{d.traceId || d.sampleTraceId || '-'}</Col>
        </Row>
        {d.pageUrl ? <div style={{ fontSize: font.caption, color: brand.textSecondary, marginTop: 4 }}>页面：{d.pageUrl}</div> : null}
        <div style={{ marginTop: 12, borderTop: '1px dashed ' + brand.border, paddingTop: 10 }}>
          <div style={{ fontSize: font.caption, color: brand.textMuted, marginBottom: 6 }}>
            同设备前后 5 分钟的其他错误（互证：错误常与网络失败、崩溃前后同因）
          </div>
          {st.detailLoading ? <div style={{ color: brand.textMuted }}>加载中…</div> : (
            st.detailEvents.length ? (
              <Table rowKey={function (r) { return String(r.id); }} size="small" dataSource={st.detailEvents}
                pagination={false} scroll={{ y: 200 }}
                columns={[
                  { title: '时间', width: 140, render: function (r) { return <span style={{ fontFamily: font.mono, fontSize: font.caption }}>{fmtTs(r.occurredAt)}</span>; } },
                  { title: '错误名', render: function (r) { return <span style={{ fontSize: font.caption }}>{cut(r.name || '(未命名)', 60)}</span>; } },
                  { title: '类型', width: 110, render: function (r) { var mm = kindMetaOf(r.kind); return <Tag color={mm.color} soft={mm.color + '14'}>{mm.label}</Tag>; } },
                  { title: '致命', width: 70, render: function (r) { return Number(r.fatal) === 1 ? <Tag tone="danger">兜住</Tag> : <span style={{ color: brand.textMuted }}>否</span>; } },
                ]}
              />
            ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="同设备该窗口内没有其他错误" />
          )}
        </div>
      </div>
    );
  }

  render() {
    var st = this.state;
    var self = this;
    return (
      <div style={{ padding: space.xl, overflowY: 'auto', height: '100%', background: brand.canvas }}>
        <div style={{ marginBottom: space.md }}>
          <span style={{ fontSize: font.title, fontWeight: font.weightBold }}>
            <Icon type="warning" style={{ color: semantic.danger, marginRight: 8 }} />错误分析
          </span>
          <span style={{ color: brand.textMuted, fontSize: font.caption, marginLeft: 12 }}>
            非致命异常 / 未处理 Promise / 资源加载失败 —— 崩溃之外的问题同样要看得见
          </span>
        </div>
        {this.renderKpi()}
        {this.renderTop()}
        {this.renderFilters()}
        <Card bodyStyle={{ padding: 0 }}>
          <Tabs activeKey={st.tab} onChange={this.switchTab} tabBarStyle={{ margin: '0 16px' }}>
            <TabPane tab="聚合错误" key="issues">
              <div style={{ padding: '8px 16px 4px' }}>
                <Button size="small" icon="check-square" disabled={!st.selectedIds.length}
                  onClick={function () { self.setState({ batchVisible: true }); }}>
                  批量处置{st.selectedIds.length ? '（' + st.selectedIds.length + '）' : ''}
                </Button>
                <span style={{ marginLeft: 10, color: brand.textMuted, fontSize: font.caption }}>勾选后统一改状态并逐条留痕；同指纹再上报会自动重开</span>
                <span style={{ float: 'right' }}>
                  <Button size="small" icon="file-text" onClick={this.exportIssuesCsv}>导出 CSV</Button>
                  <span style={{ marginLeft: 8, display: 'inline-block' }}>
                    <ColumnPicker
                      columns={this.issueColumns()}
                      storageKey="logan_cols_errorlist_issues"
                      hidden={st.hiddenIssueCols}
                      onChange={function (h) { self.setState({ hiddenIssueCols: h }); }}
                    />
                  </span>
                </span>
              </div>
              <Table rowKey="id" size="small" loading={st.loading} dataSource={st.issues}
                rowSelection={{ selectedRowKeys: st.selectedIds, onChange: function (keys) { self.setState({ selectedIds: keys }); } }}
                pagination={{ pageSize: 20, showSizeChanger: false }} scroll={{ x: 1480 }} columns={filterHidden(this.issueColumns(), st.hiddenIssueCols)}
                locale={{
                  emptyText: (st.summary && st.summary.available === false)
                    ? <EmptyState text="非致命错误采集未接入" hint="三端 SDK 的 reportError 接入后，这里会自动出现数据（口径与异常概览的错误率完全一致）" />
                    : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无非致命错误 —— 客户端捕获异常后会自动上报到这里" />,
                }}
              />
            </TabPane>
            <TabPane tab="错误明细" key="events">
              <div style={{ padding: '8px 16px 4px', textAlign: 'right' }}>
                <Button size="small" icon="file-text" onClick={this.exportEventsCsv}>导出 CSV</Button>
                <span style={{ marginLeft: 8, display: 'inline-block' }}>
                  <ColumnPicker
                    columns={this.eventColumns()}
                    storageKey="logan_cols_errorlist_events"
                    hidden={st.hiddenEventCols}
                    onChange={function (h) { self.setState({ hiddenEventCols: h }); }}
                  />
                </span>
              </div>
              <Table rowKey={function (r) { return String(r.id); }} size="small" loading={st.loading} dataSource={st.events}
                pagination={{ pageSize: 30, showSizeChanger: false }} scroll={{ x: 1520 }} columns={filterHidden(this.eventColumns(), st.hiddenEventCols)}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无错误明细" /> }}
              />
            </TabPane>
          </Tabs>
        </Card>
        <Modal
          title={st.detail ? ('错误明细 · ' + (st.detail.name || st.detail.title || '')) : ''}
          visible={!!st.detail}
          onCancel={function () { self.setState({ detail: null }); }}
          footer={null}
          width={960}
        >
          {this.renderDetailBody()}
        </Modal>
        <DisposeModal
          visible={!!st.disposeTarget}
          target={st.disposeTarget}
          targetType="error_issue"
          appId={st.appId}
          title={st.disposeTarget ? ('错误处置 · #' + st.disposeTarget.id) : '错误处置'}
          onClose={function () { self.setState({ disposeTarget: null }); }}
          onDone={function () { self.loadTab(); }}
        />
        <BatchDisposeModal
          visible={st.batchVisible}
          ids={st.selectedIds}
          targetType="error_issue"
          appId={st.appId}
          onClose={function () { self.setState({ batchVisible: false }); }}
          onDone={function () { self.setState({ batchVisible: false, selectedIds: [] }, function () { self.loadTab(); }); }}
        />
      </div>
    );
  }
}

export default connect()(ErrorListView);
