import React, { Component } from 'react';
import { connect } from 'react-redux';
import moment from 'moment';
import {
  Card, Table, Tag, Select, Input, InputNumber, Button, Icon, Tooltip, Badge, message, Modal,
  Switch, Popconfirm, Empty, Spin, Alert,
} from 'antd';
import {
  fetchAlertRulesApi, saveAlertRuleApi, enableAlertRuleApi, deleteAlertRuleApi,
  fetchAlertRecordsApi, scanAlertApi, previewAlertApi,
} from '../../common/api';
import { getCurrentAppId, ensureApp, subscribe as subscribeApp } from '../../common/currentApp';

const Option = Select.Option;
const TextArea = Input.TextArea;

function fmtTs(ts) {
  return ts ? moment(Number(ts)).format('YYYY-MM-DD HH:mm:ss') : '-';
}

// 报警（对标 Bugly 报警设置）：规则与判定全在平台侧，通知走通用 webhook —— 平台 POST 标准 JSON，
// 钉钉 / 企业微信 / 自建接收端改一下 webhook 地址即可。指标口径与「异常概览」看板完全一致。
class AlertView extends Component {
  state = {
    appId: '',
    rules: [],
    metricOptions: [],
    records: [],
    loading: false,
    recordsLoading: false,
    editing: null,
    saving: false,
    preview: null,
    scanning: false,
  };

  componentDidMount() {
    const self = this;
    ensureApp().then(function () {
      self.setState({ appId: getCurrentAppId() });
      self.load();
      self.loadRecords();
    });
    this._unsub = subscribeApp(function (appId) {
      self.setState({ appId: appId });
      self.load();
      self.loadRecords();
    });
  }

  componentWillUnmount() {
    if (this._unsub) { this._unsub(); }
  }

  load = () => {
    const self = this;
    const appId = this.state.appId;
    if (!appId) { return; }
    this.setState({ loading: true });
    fetchAlertRulesApi({ appId: appId, limit: 200 })
      .then(function (data) {
        self.setState({
          rules: (data && data.rules) || [],
          metricOptions: (data && data.metricOptions) || [],
          loading: false,
        });
      })
      .catch(function () { self.setState({ loading: false }); });
  };

  loadRecords = () => {
    const self = this;
    const appId = this.state.appId;
    if (!appId) { return; }
    this.setState({ recordsLoading: true });
    fetchAlertRecordsApi({ appId: appId, limit: 50 })
      .then(function (rows) { self.setState({ records: rows || [], recordsLoading: false }); })
      .catch(function () { self.setState({ records: [], recordsLoading: false }); });
  };

  openEditor = (rule) => {
    const dft = {
      name: '', metric: 'crash_rate', opType: 'gte', threshold: 1, windowMinutes: 60,
      platform: '', appVersion: '', webhook: '', enabled: 1, cooldownMinutes: 30,
    };
    this.setState({ editing: Object.assign({}, dft, rule || {}), preview: null });
  };

  setEditing = (k, v) => {
    const e = Object.assign({}, this.state.editing);
    e[k] = v;
    this.setState({ editing: e });
  };

  save = async () => {
    const e = this.state.editing;
    if (!e) { return; }
    if (!String(e.name || '').trim()) { message.warning('请填写规则名称'); return; }
    if (e.threshold === null || e.threshold === undefined || e.threshold === '') { message.warning('请填写阈值'); return; }
    let operator = '';
    try { operator = String(window.localStorage.getItem('logan_operator') || '').trim(); } catch (err) { operator = ''; }
    this.setState({ saving: true });
    try {
      await saveAlertRuleApi({
        id: e.id,
        appId: this.state.appId,
        name: String(e.name).trim(),
        metric: e.metric,
        opType: e.opType,
        threshold: Number(e.threshold),
        windowMinutes: Number(e.windowMinutes || 60),
        platform: e.platform === '' ? undefined : e.platform,
        appVersion: e.appVersion || undefined,
        webhook: e.webhook || undefined,
        enabled: e.enabled ? 1 : 0,
        cooldownMinutes: Number(e.cooldownMinutes || 5),
        createdBy: operator || undefined,
      });
      message.success('规则已保存');
      this.setState({ editing: null, saving: false });
      this.load();
    } catch (err) {
      this.setState({ saving: false });
    }
  };

  preview = async () => {
    const e = this.state.editing;
    if (!e || !e.id) { message.info('先保存规则，再预览当前指标值'); return; }
    try {
      const res = await previewAlertApi({ id: e.id, windowMinutes: Number(e.windowMinutes || 60) });
      this.setState({ preview: res || null });
    } catch (err) {
      this.setState({ preview: null });
    }
  };

  scanNow = async () => {
    const self = this;
    this.setState({ scanning: true });
    try {
      const res = await scanAlertApi({});
      message.success('已扫描一轮，触发 ' + ((res && res.fired) || 0) + ' 条');
      this.loadRecords();
    } finally {
      self.setState({ scanning: false });
    }
  };

  metaOf = (m) => {
    const hit = (this.state.metricOptions || []).filter(function (o) { return o.metric === m; })[0];
    return hit || { label: m, description: '', unit: '', available: true };
  };

  columns = () => {
    const self = this;
    return [
      {
        title: '规则', dataIndex: 'name', width: 230, render: (v, r) => (
          <span>
            {v}
            {r.appVersion ? <Tag style={{ marginLeft: 6 }}>{r.appVersion}</Tag> : null}
            {r.platform ? (
              <Tag color='blue' style={{ marginLeft: 4 }}>
                {String(r.platform) === '1' ? '安卓' : String(r.platform) === '2' ? 'iOS' : '鸿蒙'}
              </Tag>
            ) : null}
          </span>
        ),
      },
      {
        title: '指标', dataIndex: 'metric', width: 170, render: (m) => {
          const meta = self.metaOf(m);
          return (
            <Tooltip title={meta.description}>
              <span>
                {meta.label}
                {meta.unit ? <span style={{ color: '#8c8c8c', fontSize: 12 }}>（{meta.unit}）</span> : null}
              </span>
            </Tooltip>
          );
        },
      },
      {
        title: '条件', width: 130, render: (v, r) => (
          <span style={{ fontFamily: 'Menlo, monospace', fontSize: 12 }}>{String(r.opType || 'gte')} {r.threshold}</span>
        ),
      },
      {
        title: '窗口 / 冷却', width: 150, render: (v, r) => (
          <span style={{ fontSize: 12 }}>{r.windowMinutes || 60} 分钟 / 冷却 {r.cooldownMinutes || 5} 分钟</span>
        ),
      },
      {
        title: 'webhook', dataIndex: 'webhook', ellipsis: true, render: (v) => (v
          ? <Tooltip title={v}><span style={{ fontSize: 12, color: '#1d4ed8' }}>{v}</span></Tooltip>
          : <Tooltip title='未配置时只落库不通知'><span style={{ color: '#d97706', fontSize: 12 }}>未配置</span></Tooltip>),
      },
      {
        title: '触发', width: 120, render: (v, r) => (
          <span style={{ fontSize: 12 }}>
            累计 {r.triggerCount || 0}
            {r.lastTriggerTime ? <div style={{ color: '#8c8c8c' }}>{fmtTs(r.lastTriggerTime)}</div> : null}
          </span>
        ),
      },
      {
        title: '启用', dataIndex: 'enabled', width: 70, render: (v, r) => (
          <Switch
            size='small'
            checked={Number(v) === 1}
            onChange={async function (ck) {
              await enableAlertRuleApi({ id: r.id, enabled: ck ? 1 : 0 });
              message.success(ck ? '已启用' : '已停用');
              self.load();
            }}
          />
        ),
      },
      {
        title: '操作', width: 150, render: (v, r) => (
          <span>
            <Button size='small' onClick={function () { self.openEditor(r); }}>编辑</Button>
            <Popconfirm
              title='删除该规则？历史触发记录会保留'
              onConfirm={async function () {
                await deleteAlertRuleApi({ id: r.id });
                message.success('已删除');
                self.load();
              }}
            >
              <Button size='small' style={{ marginLeft: 6 }}>删除</Button>
            </Popconfirm>
          </span>
        ),
      },
    ];
  };

  recordColumns = () => [
    { title: '时间', dataIndex: 'addTime', width: 160, render: (v) => <span style={{ fontSize: 12 }}>{fmtTs(v)}</span> },
    { title: '规则', dataIndex: 'ruleName', width: 200, render: (v, r) => <span>{v || ('#' + r.ruleId)}</span> },
    { title: '指标', dataIndex: 'metric', width: 140 },
    {
      title: '实测 / 阈值', width: 150, render: (v, r) => (
        <span style={{ fontFamily: 'Menlo, monospace', fontSize: 12 }}>
          <b style={{ color: '#dc2626' }}>{r.value}</b> / {r.threshold}
        </span>
      ),
    },
    {
      title: '窗口', width: 200, render: (v, r) => (
        <span style={{ fontSize: 12, color: '#8c8c8c' }}>{fmtTs(r.windowBegin)} ~ {fmtTs(r.windowEnd)}</span>
      ),
    },
    {
      title: '通知结果', dataIndex: 'webhookResult', render: (v) => (String(v || '').indexOf('ok') === 0
        ? <Badge status='success' text={<span style={{ fontSize: 12 }}>{v}</span>} />
        : <Tooltip title={v}><Badge status='warning' text={<span style={{ fontSize: 12 }}>{v || '未配置'}</span>} /></Tooltip>),
    },
  ];

  renderEditor = () => {
    const self = this;
    const e = this.state.editing;
    if (!e) { return null; }
    const opts = this.state.metricOptions || [];
    const meta = opts.filter(function (o) { return o.metric === e.metric; })[0] || { label: e.metric, description: '', available: true };
    return (
      <Modal
        title={e.id ? '编辑报警规则' : '新建报警规则'}
        visible
        onCancel={function () { self.setState({ editing: null }); }}
        onOk={this.save}
        confirmLoading={this.state.saving}
        okText='保存'
        width={640}
      >
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>
            规则名称 <span style={{ color: '#dc2626' }}>*</span>
          </div>
          <Input value={e.name} onChange={function (ev) { self.setEditing('name', ev.target.value); }} placeholder='如：崩溃率超过 5% 报警' style={{ width: 320 }} />
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>监控指标</div>
          <Select value={e.metric} style={{ width: 320 }} onChange={function (v) { self.setEditing('metric', v); }}>
            {opts.map(function (o) {
              return (
                <Option key={o.metric} value={o.metric} disabled={o.available === false}>
                  {o.label}{o.available === false ? '（采集未接入）' : ''}
                </Option>
              );
            })}
          </Select>
          <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 4 }}>{meta.description}</div>
        </div>
        <div style={{ display: 'flex', alignItems: 'flex-end', marginBottom: 12, flexWrap: 'wrap' }}>
          <div style={{ marginRight: 12 }}>
            <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>判定方向</div>
            <Select value={e.opType} style={{ width: 120 }} onChange={function (v) { self.setEditing('opType', v); }}>
              <Option value='gte'>大于等于</Option>
              <Option value='gt'>大于</Option>
              <Option value='lte'>小于等于</Option>
              <Option value='lt'>小于</Option>
            </Select>
          </div>
          <div style={{ marginRight: 12 }}>
            <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>
              阈值 <span style={{ color: '#dc2626' }}>*</span>
            </div>
            <InputNumber value={e.threshold} onChange={function (v) { self.setEditing('threshold', v); }} style={{ width: 120 }} />
          </div>
          <div style={{ marginRight: 12 }}>
            <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>统计窗口（分钟）</div>
            <InputNumber value={e.windowMinutes} min={1} max={43200} onChange={function (v) { self.setEditing('windowMinutes', v); }} style={{ width: 120 }} />
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>冷却（分钟）</div>
            <InputNumber value={e.cooldownMinutes} min={5} max={43200} onChange={function (v) { self.setEditing('cooldownMinutes', v); }} style={{ width: 110 }} />
          </div>
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>webhook 地址（钉钉 / 企业微信机器人 / 自建接收端）</div>
          <TextArea
            rows={2}
            value={e.webhook}
            onChange={function (ev) { self.setEditing('webhook', ev.target.value); }}
            placeholder='留空则只落库不通知；平台以 POST + application/json 发送标准报警 JSON'
          />
        </div>
        <div style={{ marginBottom: 8 }}>
          <span style={{ fontSize: 12, color: '#8c8c8c', marginRight: 8 }}>
            <Switch size='small' checked={Number(e.enabled) === 1} onChange={function (ck) { self.setEditing('enabled', ck ? 1 : 0); }} />
            <span style={{ marginLeft: 6 }}>启用</span>
          </span>
        </div>
        {e.id ? (
          <div style={{ marginTop: 8 }}>
            <Button size='small' onClick={this.preview}>预览当前指标值</Button>
            {this.state.preview ? (
              <span style={{ marginLeft: 10, fontSize: 12 }}>
                {this.state.preview.available
                  ? <span style={{ color: '#059669' }}>当前值 {this.state.preview.value}，判定 {e.opType} 阈值 {e.threshold}</span>
                  : <span style={{ color: '#d97706' }}>{this.state.preview.note || '该指标当前不可用，不会触发报警'}</span>}
              </span>
            ) : null}
          </div>
        ) : (
          <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 8 }}>保存后可「预览当前指标值」，确认口径符合预期再启用。</div>
        )}
      </Modal>
    );
  };

  render() {
    const st = this.state;
    const self = this;
    return (
      <div style={{ padding: 24, overflowY: 'auto', height: '100%', background: '#f5f7fa' }}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
          <span style={{ fontSize: 20, fontWeight: 700, color: '#111827' }}>
            <Icon type='notification' style={{ color: '#d97706', marginRight: 8 }} />报警
          </span>
          <span style={{ marginLeft: 12, color: '#8c8c8c', fontSize: 13 }}>appId: {st.appId || '-'}</span>
          <Button icon='plus' type='primary' style={{ marginLeft: 'auto', marginRight: 8 }} onClick={function () { self.openEditor(null); }}>新建规则</Button>
          <Button icon='thunderbolt' loading={st.scanning} onClick={this.scanNow} style={{ marginRight: 8 }}>立即扫描</Button>
          <Button icon='reload' onClick={function () { self.load(); self.loadRecords(); }}>刷新</Button>
        </div>
        <Alert
          type='info'
          showIcon
          style={{ marginBottom: 12 }}
          message='通知走通用 webhook：平台以 POST + JSON 发送，钉钉 / 企业微信群机器人或自建接收端直接填地址即可；未配置地址时只落库不通知。'
          description='指标口径与「异常概览」看板完全一致；分母为 0（该时间段无联网设备）时不判定、不报警，宁缺毋假。'
        />
        <Card bodyStyle={{ padding: '8px 16px 16px' }} style={{ marginBottom: 16, borderRadius: 8 }}>
          <Table
            rowKey='id'
            size='small'
            loading={st.loading}
            dataSource={st.rules}
            columns={this.columns()}
            pagination={false}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='还没有报警规则 —— 新建一条，让崩溃率或失败请求超阈值时自动通知' /> }}
          />
        </Card>
        <Card title='最近触发记录' bodyStyle={{ padding: '8px 16px 16px' }} style={{ borderRadius: 8 }}>
          {st.recordsLoading ? <Spin /> : (
            <Table
              rowKey={function (r) { return String(r.id); }}
              size='small'
              dataSource={st.records}
              columns={this.recordColumns()}
              pagination={{ pageSize: 10, showSizeChanger: false }}
              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='暂无触发记录' /> }}
            />
          )}
        </Card>
        {this.renderEditor()}
      </div>
    );
  }
}

export default connect()(AlertView);
