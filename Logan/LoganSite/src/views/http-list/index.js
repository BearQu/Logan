import React, { Component } from 'react';
import { connect } from 'react-redux';
import moment from 'moment';
import {
  Card, Row, Col, Table, Tag, Select, Input, Button, Icon, Tooltip, Badge, message, Modal,
  DatePicker, Tabs, Spin, Empty,
} from 'antd';
import {
  fetchHttpListApi, fetchHttpSummaryApi, fetchHttpIssuesApi, fetchHttpAroundApi, updateHttpIssueStatusApi,
  reportIssueOpApi,
} from '../../common/api';
import { getCurrentAppId, ensureApp, subscribe as subscribeApp } from '../../common/currentApp';
import { HTTP_KIND_META, ERRKIND_META, HTTP_STATUS_META, SLOW_HINT_MS, statusColor } from '../../consts/http';
import { DisposeModal, BatchDisposeModal, rememberedOperator } from '../../common/components/ui/dispose';
import { exportCsv, ColumnPicker, filterHidden } from '../../common/components/ui/exportCsv';

const Option = Select.Option;
const RangePicker = DatePicker.RangePicker;
const TabPane = Tabs.TabPane;

const EMPTY_FILTERS = {
  kind: '',
  errKind: '',
  slowOnly: false,
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

function fmtMs(v) {
  var n = Number(v || 0);
  if (n >= 1000) { return (n / 1000).toFixed(2) + ' s'; }
  return n + ' ms';
}

function cut(s, n) {
  var t = String(s == null ? '' : s);
  return t.length > n ? t.slice(0, n) + '…' : t;
}

function fmtTime(v) {
  return v ? moment(Number(v)).format('MM-DD HH:mm:ss') : '-';
}

/**
 * 网络监控页：慢请求 / 失败请求 / H5 请求，全部由三端 SDK 自动采集上报（默认开启，SDK 侧一行可关）。
 * 数据来自 /logan/http/*；聚合问题按指纹归并，标记已修复后同指纹再上报会自动重开并保留历史结论。
 */
class HttpListView extends Component {

  state = {
    appId: '',
    filters: Object.assign({}, EMPTY_FILTERS),
    applied: Object.assign({}, EMPTY_FILTERS),
    tab: 'issues',
    summary: null,
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

  /** 导出当前 Tab 数据为 CSV（纯前端，带 BOM，Excel 打开中文不乱码）。 */
  exportIssuesCsv = () => {
    var cols = [
      { title: '问题ID', dataIndex: 'id' },
      { title: '来源', dataIndex: 'kind' },
      { title: '方法', dataIndex: 'method' },
      { title: '地址', dataIndex: 'sampleUrl' },
      { title: '归一化地址', dataIndex: 'urlNorm' },
      { title: '状态码', dataIndex: 'status' },
      { title: '错误类别', dataIndex: 'errKind' },
      { title: '错误信息', dataIndex: 'errMsg' },
      { title: '次数', dataIndex: 'eventCount' },
      { title: '平均耗时ms', dataIndex: 'avgMs' },
      { title: '最慢耗时ms', dataIndex: 'maxMs' },
      { title: '处置状态', dataIndex: 'statusFlag', csvText: function (r) { var m = HTTP_STATUS_META[Number(r.statusFlag)]; return m ? m.label : r.statusFlag; } },
      { title: '处理人', dataIndex: 'assignee' },
      { title: '重开次数', dataIndex: 'reopenCount' },
      { title: '首次发现', dataIndex: 'firstSeen', csvText: function (r) { return r.firstSeen ? moment(Number(r.firstSeen)).format('YYYY-MM-DD HH:mm:ss') : ''; } },
      { title: '最近发生', dataIndex: 'lastSeen', csvText: function (r) { return r.lastSeen ? moment(Number(r.lastSeen)).format('YYYY-MM-DD HH:mm:ss') : ''; } },
      { title: '处置结论', dataIndex: 'lastFixNote' },
      { title: '指纹', dataIndex: 'fingerprint' },
    ];
    var n = exportCsv(cols, this.state.issues, '网络问题_' + (this.state.appId || '') + '_' + moment().format('YYYYMMDD_HHmm'));
    if (!n) { message.warning('当前没有可导出的数据'); return; }
    message.success('已导出 ' + n + ' 条网络问题');
  };

  exportEventsCsv = () => {
    var cols = [
      { title: '事件ID', dataIndex: 'id' },
      { title: '发生时间', dataIndex: 'occurredAt', csvText: function (r) { return r.occurredAt ? moment(Number(r.occurredAt)).format('YYYY-MM-DD HH:mm:ss') : ''; } },
      { title: '来源', dataIndex: 'kind' },
      { title: '方法', dataIndex: 'method' },
      { title: '地址', dataIndex: 'url' },
      { title: '状态码', dataIndex: 'status' },
      { title: '错误类别', dataIndex: 'errKind' },
      { title: '错误信息', dataIndex: 'errMsg' },
      { title: '耗时ms', dataIndex: 'durationMs' },
      { title: '连接ms', dataIndex: 'connectMs' },
      { title: '首字节ms', dataIndex: 'ttfbMs' },
      { title: '响应字节', dataIndex: 'respBytes' },
      { title: '是否慢请求', dataIndex: 'slow', csvText: function (r) { return Number(r.slow) === 1 ? '是' : '否'; } },
      { title: '设备', dataIndex: 'deviceId' },
      { title: '网络类型', dataIndex: 'networkType' },
      { title: '应用版本', dataIndex: 'appVersion' },
      { title: '页面地址', dataIndex: 'pageUrl' },
      { title: '链路ID', dataIndex: 'traceId' },
      { title: '指纹', dataIndex: 'fingerprint' },
    ];
    var n = exportCsv(cols, this.state.events, '网络请求明细_' + (this.state.appId || '') + '_' + moment().format('YYYYMMDD_HHmm'));
    if (!n) { message.warning('当前没有可导出的数据'); return; }
    message.success('已导出 ' + n + ' 条网络事件');
  };

  loadAll = () => {
    var self = this;
    var appId = this.state.appId;
    if (!appId) { return Promise.resolve(); }
    var tp = timeParams(this.state.applied);
    this.setState({ loading: true });
    return fetchHttpSummaryApi(Object.assign({ appId: appId }, tp))
      .then(function (s) { self.setState({ summary: s || null }); })
      .catch(function () { self.setState({ summary: null }); })
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
      if (a.errKind) { p1.errKind = a.errKind; }
      if (a.statusFlag !== '' && a.statusFlag !== null && a.statusFlag !== undefined) { p1.statusFlag = Number(a.statusFlag); }
      return fetchHttpIssuesApi(p1)
        .then(function (res) { self.setState({ issues: (res && res.items) || [], events: [], loading: false }); })
        .catch(function () { self.setState({ issues: [], events: [], loading: false }); });
    }
    var p2 = Object.assign({ appId: appId, limit: 300 }, tp);
    if (a.kind) { p2.kind = a.kind; }
    if (a.errKind) { p2.errKind = a.errKind; }
    if (a.slowOnly) { p2.slowOnly = true; }
    if (a.deviceId) { p2.deviceId = a.deviceId; }
    if (a.keyword) { p2.keyword = a.keyword; }
    return fetchHttpListApi(p2)
      .then(function (res) { self.setState({ events: (res && res.items) || [], issues: [], loading: false }); })
      .catch(function () { self.setState({ events: [], issues: [], loading: false }); });
  };

  apply = () => {
    this.setState({ applied: Object.assign({}, this.state.filters) });
    this.loadAll();
  };

  reset = () => {
    this.setState({ filters: Object.assign({}, EMPTY_FILTERS), applied: Object.assign({}, EMPTY_FILTERS) });
    this.loadAll();
  };

  switchTab = (tab) => {
    this.setState({ tab: tab });
    this.loadTab();
  };

  // 快捷标记：走 /logan/issue/op.json 留痕；未记住操作人时先开处置弹窗补填。
  markStatus = (record, statusFlag) => {
    var self = this;
    var operator = rememberedOperator();
    if (!operator) {
      message.warning('请先在处置弹窗填写操作人（状态变更需要留痕到人）');
      this.setState({ disposeTarget: record });
      return;
    }
    return reportIssueOpApi({
      targetType: 'http_issue', targetId: record.id, appId: this.state.appId,
      op: 'status', status: statusFlag, operator: operator,
    }).then(function () {
      message.success('已标记为「' + HTTP_STATUS_META[statusFlag].label + '」并记录留痕');
      self.loadTab();
    });
  };

  openDetail = (record) => {
    var self = this;
    this.setState({ detail: record, detailEvents: [], detailLoading: true });
    var devices = [];
    try { devices = JSON.parse(record.affectedDevices || '[]'); } catch (e) { devices = []; }
    var deviceId = devices.length ? devices[devices.length - 1] : record.sampleDeviceId;
    if (!deviceId) { this.setState({ detailLoading: false }); return; }
    var span = (Math.max(60, Math.ceil(Number(record.avgMs || 0) / 1000) + 120)) * 1000;
    fetchHttpAroundApi({
      appId: this.state.appId,
      deviceId: deviceId,
      beginTime: Number(record.lastSeen) - span,
      endTime: Number(record.lastSeen) + 15000,
      limit: 120,
    }).then(function (rows) {
      self.setState({ detailEvents: rows || [], detailLoading: false });
    }).catch(function () {
      self.setState({ detailEvents: [], detailLoading: false });
    });
  };

  renderKpi() {
    var s = this.state.summary;
    var cells = [
      { title: '网络事件总数', value: s ? s.total : 0, color: '#262626', icon: 'global' },
      { title: '慢请求(>' + SLOW_HINT_MS + 'ms)', value: s ? s.slowCount : 0, color: '#faad14', icon: 'clock-circle' },
      { title: 'HTTP 错误', value: s ? s.httpErrorCount : 0, color: '#fa8c16', icon: 'warning' },
      { title: '传输失败', value: s ? s.transportErrorCount : 0, color: '#f5222d', icon: 'disconnect' },
      { title: '平均耗时', value: s ? fmtMs(s.avgMs) : '0 ms', color: '#1890ff', icon: 'dashboard' },
      { title: '最慢', value: s ? fmtMs(s.maxMs) : '0 ms', color: '#722ed1', icon: 'thunderbolt' },
      { title: '涉及设备', value: s ? s.deviceCount : 0, color: '#13c2c2', icon: 'mobile' },
    ];
    return (
      <Row gutter={12} style={{ marginBottom: 16 }} type='flex'>
        {cells.map(function (c) {
          return (
            <Col key={c.title} style={{ flex: '0 0 14.28%' }}>
              <Card bodyStyle={{ padding: '12px 14px' }}>
                <div style={{ color: '#8c8c8c', fontSize: 12 }}>
                  <Icon type={c.icon} style={{ marginRight: 6, color: c.color }} />{c.title}
                </div>
                <div style={{ fontSize: 20, fontWeight: 700, color: c.color, marginTop: 4 }}>{c.value}</div>
              </Card>
            </Col>
          );
        })}
      </Row>
    );
  }

  renderFilters() {
    var f = this.state.filters;
    var self = this;
    return (
      <Card bodyStyle={{ padding: '12px 16px' }} style={{ marginBottom: 16 }}>
        <span style={{ marginRight: 10 }}>
          <span style={{ color: '#8c8c8c', fontSize: 12, marginRight: 4 }}>来源</span>
          <Select size='small' style={{ width: 110 }} value={f.kind || 'all'}
            onChange={function (v) { self.setFilter('kind', v === 'all' ? '' : v); }}>
            <Option value='all'>全部</Option>
            {Object.keys(HTTP_KIND_META).map(function (k) {
              return <Option key={k} value={k}>{HTTP_KIND_META[k].label}</Option>;
            })}
          </Select>
        </span>
        <span style={{ marginRight: 10 }}>
          <span style={{ color: '#8c8c8c', fontSize: 12, marginRight: 4 }}>错误类别</span>
          <Select size='small' style={{ width: 110 }} value={f.errKind || 'all'}
            onChange={function (v) { self.setFilter('errKind', v === 'all' ? '' : v); }}>
            <Option value='all'>全部</Option>
            {Object.keys(ERRKIND_META).map(function (k) {
              return <Option key={k} value={k}>{ERRKIND_META[k].label}</Option>;
            })}
          </Select>
        </span>
        <span style={{ marginRight: 10 }}>
          <span style={{ color: '#8c8c8c', fontSize: 12, marginRight: 4 }}>只看慢请求</span>
          <Select size='small' style={{ width: 76 }} value={f.slowOnly ? '1' : '0'}
            onChange={function (v) { self.setFilter('slowOnly', v === '1'); }}>
            <Option value='0'>否</Option>
            <Option value='1'>是</Option>
          </Select>
        </span>
        <span style={{ marginRight: 10 }}>
          <span style={{ color: '#8c8c8c', fontSize: 12, marginRight: 4 }}>处置状态</span>
          <Select size='small' style={{ width: 96 }} value={f.statusFlag === '' ? 'all' : String(f.statusFlag)}
            onChange={function (v) { self.setFilter('statusFlag', v === 'all' ? '' : v); }}>
            <Option value='all'>全部</Option>
            <Option value='0'>未处理</Option>
            <Option value='1'>处理中</Option>
            <Option value='2'>已修复</Option>
          </Select>
        </span>
        <span style={{ marginRight: 10 }}>
          <Input size='small' style={{ width: 170 }} placeholder='设备码' value={f.deviceId}
            onChange={function (e) { self.setFilter('deviceId', e.target.value); }} />
        </span>
        <span style={{ marginRight: 10 }}>
          <Input size='small' style={{ width: 200 }} placeholder='关键字（地址/错误信息）' value={f.keyword}
            onChange={function (e) { self.setFilter('keyword', e.target.value); }}
            onPressEnter={this.apply} />
        </span>
        <span style={{ marginRight: 10 }}>
          <RangePicker size='small' showTime format='YYYY-MM-DD HH:mm:ss' value={f.range}
            onChange={function (v) { self.setFilter('range', v); }} />
        </span>
        <Button size='small' type='primary' icon='search' onClick={this.apply} style={{ marginRight: 8 }}>查询</Button>
        <Button size='small' icon='reload' onClick={this.reset}>重置</Button>
        <div style={{ marginTop: 8, color: '#8c8c8c', fontSize: 12 }}>
          <Icon type='info-circle' style={{ marginRight: 4 }} />
          数据由三端 SDK 自动采集（原生网络库与 H5 容器均自动装配，业务无需改造）；SDK 侧 LoganHttp.get().setEnabled(false) / LoganH5Bridge.setEnabled(false) 一行可关。
        </div>
      </Card>
    );
  }

  kindTag(kind) {
    var m = HTTP_KIND_META[kind] || { label: kind || '-', color: 'default' };
    return <Tag color={m.color}>{m.label}</Tag>;
  }

  errTag(errKind) {
    if (!errKind) { return <span style={{ color: '#8c8c8c' }}>—</span>; }
    var m = ERRKIND_META[errKind] || { label: errKind, color: 'default' };
    return <Tag color={m.color}>{m.label}</Tag>;
  }

  statusCell(status) {
    if (!status) { return <span style={{ color: '#8c8c8c' }}>—</span>; }
    return <span style={{ color: statusColor(status), fontWeight: 700 }}>{status}</span>;
  }

  issueColumns() {
    var self = this;
    return [
      { title: '问题', width: 330, render: function (r) {
        return (
          <div>
            <div style={{ fontWeight: 600, color: '#262626' }}>{r.title || cut(r.sampleUrl, 90)}</div>
            <div style={{ fontFamily: 'Menlo, monospace', fontSize: 12, color: '#8c8c8c' }}>
              {(r.method || 'GET') + ' ' + cut(r.host, 60) + (r.path ? cut(r.path, 80) : '')}
            </div>
          </div>
        );
      } },
      { title: '来源', width: 90, render: function (r) { return self.kindTag(r.kind); } },
      { title: '状态码', width: 80, render: function (r) { return self.statusCell(r.status); } },
      { title: '错误类别', width: 95, render: function (r) { return self.errTag(r.errKind); } },
      { title: '次数', width: 170, render: function (r) {
        return (
          <span>
            <span style={{ color: '#f5222d', fontWeight: 700 }}>{r.errorCount || 0}</span>
            <span style={{ color: '#8c8c8c', marginLeft: 6 }}>/ 慢 {r.slowCount || 0}</span>
            <span style={{ color: '#8c8c8c', marginLeft: 6 }}>/ 共 {r.totalCount || 0}</span>
          </span>
        );
      } },
      { title: '耗时(均/最)', width: 140, render: function (r) { return fmtMs(r.avgMs) + ' / ' + fmtMs(r.maxMs); } },
      { title: '最近发生', width: 120, render: function (r) { return fmtTime(r.lastSeen); } },
      { title: '处理人', width: 96, render: function (r) {
        return r.assignee
          ? <span style={{ fontSize: 12, color: '#1d4ed8' }}>{r.assignee}</span>
          : <span style={{ color: '#bfbfbf', fontSize: 12 }}>未指派</span>;
      } },
      { title: '标记', width: 130, render: function (r) {
        var m = HTTP_STATUS_META[r.statusFlag] || HTTP_STATUS_META[0];
        return (
          <span>
            <Tag color={m.color}>{m.label}</Tag>
            {r.reopenCount ? <Tooltip title='标记已修复后又出现了'><Tag color='red'>重开 {r.reopenCount}</Tag></Tooltip> : null}
          </span>
        );
      } },
      { title: '操作', width: 270, render: function (r) {
        return (
          <span>
            <Button size='small' onClick={function () { self.openDetail(r); }}>明细</Button>
            <Button size='small' style={{ marginLeft: 6 }} onClick={function () { self.setState({ disposeTarget: r }); }}>处置</Button>
            {r.statusFlag !== 1 ? <Button size='small' style={{ marginLeft: 6 }} onClick={function () { self.markStatus(r, 1); }}>处理中</Button> : null}
            {r.statusFlag !== 2 ? <Button size='small' type='primary' style={{ marginLeft: 6 }} onClick={function () { self.markStatus(r, 2); }}>已修复</Button> : null}
            {r.statusFlag !== 0 ? <Button size='small' style={{ marginLeft: 6 }} onClick={function () { self.markStatus(r, 0); }}>重新打开</Button> : null}
          </span>
        );
      } },
    ];
  }

  eventColumns() {
    return [
      { title: '时间', width: 125, render: function (r) { return fmtTime(r.occurredAt); } },
      { title: '来源', width: 88, render: function (r) { return <span>{String(r.kind || '-')}</span>; } },
      { title: '方法', width: 76, render: function (r) { return <span style={{ fontFamily: 'Menlo, monospace', fontSize: 12 }}>{r.method || 'GET'}</span>; } },
      { title: '地址', render: function (r) {
        return (
          <Tooltip title={r.url}>
            <span style={{ fontFamily: 'Menlo, monospace', fontSize: 12 }}>{cut(r.host, 40)}{r.path ? cut(r.path, 70) : ''}</span>
          </Tooltip>
        );
      } },
      { title: '状态', width: 66, render: function (r) { return <span style={{ color: statusColor(r.status), fontWeight: 700 }}>{r.status || '—'}</span>; } },
      { title: '耗时', width: 110, render: function (r) {
        return (
          <span style={{ color: r.slow ? '#fa8c16' : '#595959', fontWeight: r.slow ? 700 : 400 }}>
            {fmtMs(r.durationMs)}
            {r.slow ? <Tag color='orange' style={{ marginLeft: 4 }}>慢</Tag> : null}
          </span>
        );
      } },
      { title: '设备', width: 150, render: function (r) {
        return <span style={{ fontSize: 12 }}>{cut(r.brand ? (r.brand + ' ' + r.model) : r.deviceId, 26)}</span>;
      } },
      { title: '网络', width: 88, render: function (r) { return <span style={{ fontSize: 12, color: '#8c8c8c' }}>{r.networkType || '-'}</span>; } },
      { title: '版本', width: 88, render: function (r) { return <span style={{ fontSize: 12, color: '#8c8c8c' }}>{r.appVersion || '-'}</span>; } },
      { title: '错误信息', width: 210, render: function (r) {
        if (!r.errMsg) { return null; }
        return <Tooltip title={r.errMsg}><span style={{ color: '#f5222d', fontSize: 12 }}>{cut(r.errMsg, 40)}</span></Tooltip>;
      } },
    ];
  }

  renderDetailBody() {
    var d = this.state.detail;
    if (!d) { return null; }
    var rows = [
      ['指纹', d.fingerprint],
      ['样例地址', d.sampleUrl],
      ['最近错误信息', d.lastErrMsg],
      ['影响设备', d.affectedDevices],
      ['首次发现', d.firstSeen ? moment(Number(d.firstSeen)).format('YYYY-MM-DD HH:mm:ss') : '-'],
      ['最近发生', d.lastSeen ? moment(Number(d.lastSeen)).format('YYYY-MM-DD HH:mm:ss') : '-'],
      ['历史处置结论', d.lastFixNote || '（无）'],
    ];
    var loading = this.state.detailLoading;
    var evs = this.state.detailEvents;
    return (
      <div>
        {rows.map(function (r) {
          return (
            <div key={String(r[0])} style={{ display: 'flex', padding: '4px 0', borderBottom: '1px dashed #f0f0f0' }}>
              <span style={{ width: 110, color: '#8c8c8c', fontSize: 12, flexShrink: 0 }}>{r[0]}</span>
              <span style={{ flex: 1, fontSize: 12, fontFamily: 'Menlo, monospace', wordBreak: 'break-all' }}>
                {String(r[1] == null ? '' : r[1])}
              </span>
            </div>
          );
        })}
        <div style={{ margin: '12px 0 8px', color: '#8c8c8c', fontSize: 12 }}>同设备该时间窗内的网络事件（用于复现与互证）</div>
        {loading ? <div style={{ textAlign: 'center', padding: 20 }}><Spin /></div> : null}
        {!loading && (!evs || evs.length === 0) ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='该时间窗内没有其它网络事件' /> : null}
        {!loading && evs && evs.length > 0 ? (
          <Table rowKey={function (r) { return String(r.id); }} size='small' dataSource={evs}
            pagination={false} scroll={{ y: 300 }} columns={this.eventColumns()} />
        ) : null}
      </div>
    );
  }

  render() {
    var st = this.state;
    var self = this;
    return (
      <div style={{ padding: 24, overflowY: 'auto', height: '100%', background: '#f5f6f8' }}>
        <div style={{ marginBottom: 12 }}>
          <span style={{ fontSize: 18, fontWeight: 700 }}>
            <Icon type='global' style={{ color: '#13c2c2', marginRight: 8 }} />网络监控
          </span>
          <span style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 12 }}>
            慢请求 / 失败请求 / H5 请求自动采集，与崩溃、业务日志互相印证
          </span>
        </div>
        {this.renderKpi()}
        {this.renderFilters()}
        <Card bodyStyle={{ padding: 0 }}>
          <Tabs activeKey={st.tab} onChange={this.switchTab} tabBarStyle={{ margin: '0 16px' }}>
            <TabPane tab='聚合问题' key='issues'>
              <div style={{ padding: '8px 16px 4px' }}>
                <Button size='small' icon='check-square' disabled={!st.selectedIds.length}
                  onClick={function () { self.setState({ batchVisible: true }); }}>
                  批量处置{st.selectedIds.length ? '（' + st.selectedIds.length + '）' : ''}
                </Button>
                <span style={{ marginLeft: 10, color: '#8c8c8c', fontSize: 12 }}>勾选后可统一改状态并逐条留痕</span>
                <span style={{ float: 'right' }}>
                  <Button size='small' icon='file-text' onClick={this.exportIssuesCsv}>导出 CSV</Button>
                  <ColumnPicker
                    columns={this.issueColumns()}
                    storageKey='logan_cols_httplist_issues'
                    hidden={st.hiddenIssueCols}
                    onChange={function (h) { self.setState({ hiddenIssueCols: h }); }}
                  />
                </span>
              </div>
              <Table rowKey='id' size='small' loading={st.loading} dataSource={st.issues}
                rowSelection={{ selectedRowKeys: st.selectedIds, onChange: function (keys) { self.setState({ selectedIds: keys }); } }}
                pagination={{ pageSize: 20, showSizeChanger: false }} scroll={{ x: 1520 }} columns={filterHidden(this.issueColumns(), st.hiddenIssueCols)}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='暂无网络问题 —— 客户端发出请求后，失败或慢请求会自动出现在这里' /> }}
              />
            </TabPane>
            <TabPane tab='请求明细' key='events'>
              <div style={{ padding: '8px 16px 4px', textAlign: 'right' }}>
                <Button size='small' icon='file-text' onClick={this.exportEventsCsv}>导出 CSV</Button>
                <ColumnPicker
                  columns={this.eventColumns()}
                  storageKey='logan_cols_httplist_events'
                  hidden={st.hiddenEventCols}
                  onChange={function (h) { self.setState({ hiddenEventCols: h }); }}
                />
              </div>
              <Table rowKey={function (r) { return String(r.id); }} size='small' loading={st.loading} dataSource={st.events}
                pagination={{ pageSize: 30, showSizeChanger: false }} scroll={{ x: 1560 }} columns={filterHidden(this.eventColumns(), st.hiddenEventCols)}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='暂无网络事件 —— 等客户端产生业务请求后自动上报' /> }}
              />
            </TabPane>
          </Tabs>
        </Card>
        <Modal
          title={st.detail ? ('问题明细 · ' + (st.detail.title || st.detail.sampleUrl || '')) : ''}
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
          targetType='http_issue'
          appId={st.appId}
          title={st.disposeTarget ? ('网络问题处置 · #' + st.disposeTarget.id) : '网络问题处置'}
          onClose={function () { self.setState({ disposeTarget: null }); }}
          onDone={function () { self.loadTab(); }}
        />
        <BatchDisposeModal
          visible={st.batchVisible}
          ids={st.selectedIds}
          targetType='http_issue'
          appId={st.appId}
          onClose={function () { self.setState({ batchVisible: false }); }}
          onDone={function () { self.setState({ batchVisible: false, selectedIds: [] }, function () { self.loadTab(); }); }}
        />
      </div>
    );
  }
}

export default connect()(HttpListView);