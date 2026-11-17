import React, { Component } from 'react';
import { Modal, Tabs, Input, Select, Button, Timeline, message, Icon, Tooltip } from 'antd';
import moment from 'moment';
import { reportIssueOpApi, batchIssueOpApi, fetchIssueOpsApi } from '../../api';
import { issueStatusMeta } from '../../theme';

const TextArea = Input.TextArea;
const Option = Select.Option;
const TabPane = Tabs.TabPane;
const OPERATOR_KEY = 'logan_operator';

const OP_LABEL = {
  status: { label: '状态变更', color: 'blue' },
  assign: { label: '指派', color: 'purple' },
  note: { label: '备注', color: 'gray' },
  batch: { label: '批量处置', color: 'blue' },
};

export function rememberedOperator() {
  try { return window.localStorage.getItem(OPERATOR_KEY) || ''; } catch (e) { return ''; }
}

function rememberOperator(v) {
  try { window.localStorage.setItem(OPERATOR_KEY, v); } catch (e) { /* 隐私模式下忽略 */ }
}

function fmtTs(ts) {
  return ts ? moment(Number(ts)).format('YYYY-MM-DD HH:mm:ss') : '-';
}

// 操作留痕时间线：谁在什么时候把问题从什么状态改到什么状态、写了什么备注。
export class OpsTimeline extends Component {
  state = { rows: [], loading: false, error: '' };

  componentDidMount() { this.load(); }

  componentDidUpdate(prev) {
    if (prev.targetType !== this.props.targetType || prev.targetId !== this.props.targetId || prev.reloadKey !== this.props.reloadKey) {
      this.load();
    }
  }

  load = async () => {
    const { targetType, targetId } = this.props;
    if (!targetType || !targetId) { return; }
    this.setState({ loading: true, error: '' });
    try {
      const rows = await fetchIssueOpsApi({ targetType: targetType, targetId: targetId, limit: 80 });
      this.setState({ rows: rows || [], loading: false });
    } catch (e) {
      this.setState({ rows: [], loading: false, error: '读取操作记录失败' });
    }
  };

  render() {
    const rows = this.state.rows;
    if (this.state.loading) { return <div style={{ color: '#8c8c8c', fontSize: 12 }}>加载中…</div>; }
    if (this.state.error) { return <div style={{ color: '#dc2626', fontSize: 12 }}>{this.state.error}</div>; }
    if (!rows.length) {
      return (
        <div style={{ color: '#8c8c8c', fontSize: 12, padding: '8px 0' }}>
          还没有操作记录 —— 处置、指派或备注后会自动留痕，包含操作人、时间与前后状态。
        </div>
      );
    }
    return (
      <Timeline style={{ marginTop: 8 }}>
        {rows.map((r) => {
          const meta = OP_LABEL[r.op] || { label: String(r.op || '操作'), color: 'blue' };
          const after = r.afterStatus == null ? null : issueStatusMeta[r.afterStatus];
          return (
            <Timeline.Item key={r.id} color={meta.color}>
              <div style={{ fontSize: 13 }}>
                <b>{meta.label}</b>
                {(r.op === 'status' && after) ? <span style={{ color: after.color, marginLeft: 6 }}>{'→ ' + after.label}</span> : null}
                <span style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 8 }}>
                  <Icon type='user' style={{ marginRight: 4 }} />{r.operator || '-'}
                </span>
                <span style={{ color: '#bfbfbf', fontSize: 12, marginLeft: 8 }}>{fmtTs(r.addTime)}</span>
              </div>
              {r.note ? <div style={{ fontSize: 12, color: '#595959', marginTop: 2, whiteSpace: 'pre-wrap' }}>{r.note}</div> : null}
            </Timeline.Item>
          );
        })}
      </Timeline>
    );
  }
}

// 单个问题的处置弹窗：状态变更 / 指派处理人 / 备注 + 操作留痕。操作人必填（平台无账号体系）。
export class DisposeModal extends Component {
  state = {
    tab: 'act',
    operator: rememberedOperator(),
    status: undefined,
    assignee: '',
    note: '',
    submitting: false,
    opsKey: 0,
  };

  componentDidUpdate(prev) {
    if (!!prev.visible !== !!this.props.visible) {
      const t = this.props.target;
      const cur = t && t.status != null ? Number(t.status) : (t && t.statusFlag != null ? Number(t.statusFlag) : undefined);
      this.setState({ tab: 'act', status: cur, assignee: (t && t.assignee) || '', note: '' });
    }
  }

  checkOperator = () => {
    const op = String(this.state.operator || '').trim();
    if (!op) {
      message.warning('请填写操作人（平台无账号体系，留痕需要真实操作人）');
      return null;
    }
    rememberOperator(op);
    return op;
  };

  submit = async (kind) => {
    const targetType = this.props.targetType;
    const target = this.props.target;
    if (!target) { return; }
    const operator = this.checkOperator();
    if (!operator) { return; }
    const status = this.state.status;
    const assignee = this.state.assignee;
    const note = String(this.state.note || '').trim();
    if (kind === 'status' && status == null) { message.warning('请选择处理状态'); return; }
    if (kind === 'assign' && !String(assignee || '').trim()) { message.warning('请填写处理人'); return; }
    if (kind === 'note' && !note) { message.warning('请填写备注内容'); return; }
    const body = { targetType: targetType, targetId: target.id, appId: this.props.appId, operator: operator };
    if (kind === 'status') { body.op = 'status'; body.status = Number(status); if (note) { body.note = note; } }
    if (kind === 'assign') { body.op = 'assign'; body.assignee = String(assignee).trim(); if (note) { body.note = note; } }
    if (kind === 'note') { body.op = 'note'; body.note = note; }
    this.setState({ submitting: true });
    try {
      await reportIssueOpApi(body);
      message.success('已记录处置，操作留痕已写入');
      this.setState({ submitting: false, opsKey: this.state.opsKey + 1 });
      if (this.props.onDone) { this.props.onDone(); }
    } catch (e) {
      this.setState({ submitting: false });
    }
  };

  statusOptions = () => {
    return Object.keys(issueStatusMeta).map((k) => (
      <Option key={k} value={Number(k)}>{issueStatusMeta[k].label}</Option>
    ));
  };

  render() {
    const visible = this.props.visible;
    const target = this.props.target;
    const targetType = this.props.targetType;
    const tab = this.state.tab;
    return (
      <Modal
        title={this.props.title || '问题处置'}
        visible={!!visible}
        onCancel={this.props.onClose}
        footer={<Button onClick={this.props.onClose}>关闭</Button>}
        width={680}
      >
        <Tabs activeKey={tab} onChange={(k) => this.setState({ tab: k })}>
          <TabPane tab='处置' key='act'>
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>
                操作人 <span style={{ color: '#dc2626' }}>*</span>
              </div>
              <Input
                value={this.state.operator}
                onChange={(e) => this.setState({ operator: e.target.value })}
                placeholder='你的姓名或工号，用于操作留痕'
                style={{ width: 260 }}
              />
            </div>
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>处理状态</div>
              <Select value={this.state.status} style={{ width: 260 }} placeholder='选择状态' onChange={(v) => this.setState({ status: v })}>
                {this.statusOptions()}
              </Select>
            </div>
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>处理人</div>
              <Input
                value={this.state.assignee}
                onChange={(e) => this.setState({ assignee: e.target.value })}
                placeholder='指派给谁跟进'
                style={{ width: 260 }}
              />
            </div>
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>备注 / 结论</div>
              <TextArea
                rows={3}
                value={this.state.note}
                onChange={(e) => this.setState({ note: e.target.value })}
                placeholder='根因、改动文件、commit、验证方式…标记为已修复时建议写清结论，同指纹再上报会自动重开并带上这段历史'
              />
            </div>
            <div>
              <Button type='primary' loading={this.state.submitting} onClick={() => this.submit('status')}>保存状态</Button>
              <Button style={{ marginLeft: 8 }} loading={this.state.submitting} onClick={() => this.submit('assign')}>指派</Button>
              <Button style={{ marginLeft: 8 }} loading={this.state.submitting} onClick={() => this.submit('note')}>仅备注</Button>
              <Tooltip title='标记为已修复或已忽略会记录完结时间；退回未处理 / 处理中会清掉完结时间'>
                <Icon type='info-circle' style={{ marginLeft: 10, color: '#bfbfbf' }} />
              </Tooltip>
            </div>
          </TabPane>
          <TabPane tab='操作记录' key='ops'>
            <OpsTimeline targetType={targetType} targetId={target ? target.id : null} reloadKey={this.state.opsKey} />
          </TabPane>
        </Tabs>
      </Modal>
    );
  }
}

// 批量处置：勾选多条后统一改状态，逐条留痕；平台取不到的记录进 skipped 并如实报告。
export class BatchDisposeModal extends Component {
  state = { operator: rememberedOperator(), status: 1, note: '', submitting: false };

  submit = async () => {
    const operator = String(this.state.operator || '').trim();
    if (!operator) { message.warning('请填写操作人'); return; }
    rememberOperator(operator);
    const ids = this.props.ids;
    if (!ids || !ids.length) { message.warning('未选中任何记录'); return; }
    const note = String(this.state.note || '').trim();
    const body = {
      targetType: this.props.targetType,
      ids: ids,
      status: Number(this.state.status),
      operator: operator,
      appId: this.props.appId,
    };
    if (note) { body.note = note; }
    this.setState({ submitting: true });
    try {
      const res = await batchIssueOpApi(body);
      const done = (res && res.done) || 0;
      const skipped = (res && res.skipped) || [];
      message.success('已处置 ' + done + ' 条' + (skipped.length ? '，跳过 ' + skipped.length + ' 条（记录不存在）' : ''));
      this.setState({ submitting: false });
      if (this.props.onDone) { this.props.onDone(); }
    } catch (e) {
      this.setState({ submitting: false });
    }
  };

  render() {
    const ids = this.props.ids || [];
    return (
      <Modal
        title={'批量处置 ' + ids.length + ' 条'}
        visible={!!this.props.visible}
        onCancel={this.props.onClose}
        confirmLoading={this.state.submitting}
        onOk={this.submit}
        okText='确认处置'
        width={560}
      >
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>
            操作人 <span style={{ color: '#dc2626' }}>*</span>
          </div>
          <Input value={this.state.operator} onChange={(e) => this.setState({ operator: e.target.value })} style={{ width: 240 }} placeholder='姓名或工号' />
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>统一改为</div>
          <Select value={this.state.status} style={{ width: 240 }} onChange={(v) => this.setState({ status: v })}>
            {Object.keys(issueStatusMeta).map((k) => (
              <Option key={k} value={Number(k)}>{issueStatusMeta[k].label}</Option>
            ))}
          </Select>
        </div>
        <div>
          <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>统一备注 / 结论（可选）</div>
          <TextArea rows={3} value={this.state.note} onChange={(e) => this.setState({ note: e.target.value })} placeholder='如：统一归类为弱网环境问题，本轮不处理' />
        </div>
      </Modal>
    );
  }
}
