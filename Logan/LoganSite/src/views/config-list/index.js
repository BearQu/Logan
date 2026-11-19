import React, { Component } from "react";
import { connect } from "react-redux";
import {
  Card, Table, Tag, Button, Modal, Form, Input, Popconfirm, message, Tooltip, Select,
} from "antd";
import moment from "moment";
import { fetchConfigListApi, saveConfigApi, deleteConfigApi, fetchAppListApi } from "../../common/api";
import { getCurrentAppId, setCurrentAppId, subscribe } from "../../common/currentApp";

/**
 * 远程配置（对标 Bugly 收费版「远程配置」）：平台侧维护键值对，SDK 按 appId 增量拉取。
 * 版本号语义见服务端 LoganConfigService：保存/删除都递增 app 维度 version，
 * 删除是墓碑（列表里显示「已删除」直到清理），SDK 下一轮拉取后移除本地键。
 */
class ConfigList extends Component {
  state = { appId: getCurrentAppId(), rows: [], loading: true, apps: [], visible: false, submitting: false, editing: null };

  componentDidMount() {
    this.unsubscribe = subscribe(() => this.setState({ appId: getCurrentAppId() }, this.load));
    fetchAppListApi().then((l) => this.setState({ apps: Array.isArray(l) ? l : [] })).catch(() => {});
    this.load();
  }

  componentWillUnmount() {
    if (this.unsubscribe) { this.unsubscribe(); }
  }

  componentDidUpdate(prevProps, prevState) {
    if (prevState.appId !== this.state.appId) { this.load(); }
  }

  load = async () => {
    const { appId } = this.state;
    if (!appId) { this.setState({ rows: [], loading: false }); return; }
    this.setState({ loading: true });
    try {
      const rows = await fetchConfigListApi(appId);
      this.setState({ rows: rows || [], loading: false });
    } catch (e) {
      this.setState({ rows: [], loading: false });
    }
  };

  openEdit = (row) => {
    this.setState({ visible: true, editing: row || null });
  };

  closeEdit = () => {
    const { form } = this.props;
    if (form) { form.resetFields(); }
    this.setState({ visible: false, editing: null });
  };

  submit = (e) => {
    e.preventDefault();
    const { form } = this.props;
    form.validateFields(async (err, values) => {
      if (err) { return; }
      this.setState({ submitting: true });
      try {
        await saveConfigApi({
          appId: this.state.appId,
          configKey: String(values.configKey || "").trim(),
          configValue: values.configValue == null ? "" : String(values.configValue),
          remark: values.remark || "",
        });
        message.success("已保存，SDK 将在下一轮拉取后生效");
        this.closeEdit();
        this.load();
      } catch (err2) {
        // axios 拦截器已弹错
      } finally {
        this.setState({ submitting: false });
      }
    });
  };

  remove = async (row) => {
    try {
      await deleteConfigApi({ appId: this.state.appId, configKey: row.configKey });
      message.success("已删除（墓碑下发，SDK 下一轮移除本地键）");
      this.load();
    } catch (e) { /* 拦截器已弹错 */ }
  };

  render() {
    const { appId, rows, loading, apps, visible, submitting, editing } = this.state;
    const { getFieldDecorator } = this.props.form;
    const columns = [
      { title: "键", dataIndex: "configKey", width: 220, render: (t) => <code>{t}</code> },
      { title: "值", dataIndex: "configValue", render: (t, r) => (
        <Tooltip title={r.enabled === 0 ? "已删除（墓碑）" : t}>
          <span style={r.enabled === 0 ? { color: "#bbb", textDecoration: "line-through" } : { wordBreak: "break-all" }}>{t || <span style={{ color: "#bbb" }}>(空)</span>}</span>
        </Tooltip>
      ) },
      { title: "版本", dataIndex: "version", width: 70 },
      { title: "备注", dataIndex: "remark", width: 140, render: (t) => t || "-" },
      { title: "更新时间", dataIndex: "updateTime", width: 160, render: (t) => (t ? moment(t).format("MM-DD HH:mm:ss") : "-") },
      { title: "状态", dataIndex: "enabled", width: 80, render: (v) => (v === 1 ? <Tag color="green">生效</Tag> : <Tag>已删除</Tag>) },
      { title: "操作", width: 130, render: (t, r) => (
        <span>
          <Button size="small" onClick={() => this.openEdit(r)}>编辑</Button>
          <Popconfirm title="删除该键并下发墓碑？" onConfirm={() => this.remove(r)}>
            <Button size="small" style={{ marginLeft: 8 }}>删除</Button>
          </Popconfirm>
        </span>
      ) },
    ];
    return (
      <Card
        title="远程配置"
        extra={
          <span>
            <Select
              style={{ width: 260, marginRight: 12 }}
              value={appId || undefined}
              placeholder="选择应用"
              onChange={(v) => { setCurrentAppId(v); this.setState({ appId: v }); }}
            >
              {apps.map((a) => <Select.Option key={a.appId} value={a.appId}>{a.appName || a.appId}</Select.Option>)}
            </Select>
            <Button type="primary" disabled={!appId} onClick={() => this.openEdit(null)}>新建配置</Button>
          </span>
        }
      >
        <Table rowKey="id" columns={columns} dataSource={rows} loading={loading} pagination={false}
          locale={{ emptyText: appId ? "还没有配置项，点右上「新建配置」" : "先选择一个应用" }} />
        <Modal title={editing ? "编辑配置（覆盖即新版本）" : "新建配置"} visible={visible}
          okText="保存" confirmLoading={submitting} onOk={this.submit} onCancel={this.closeEdit} destroyOnClose>
          <Form layout="vertical" onSubmit={this.submit}>
            <Form.Item label="键（字母/数字/. - _，1-128）">
              {getFieldDecorator("configKey", {
                initialValue: editing ? editing.configKey : "",
                rules: [
                  { required: true, message: "请输入键" },
                  { pattern: /^[A-Za-z0-9_.\-]{1,128}$/, message: "只允许字母、数字、点、横线、下划线" },
                ],
              })(<Input placeholder="如 feature_new_scan" disabled={!!editing} />)}
            </Form.Item>
            <Form.Item label="值（最长 2048）">
              {getFieldDecorator("configValue", { initialValue: editing ? editing.configValue : "" })(
                <Input.TextArea rows={3} placeholder="true / 5000 / json 字符串…" />
              )}
            </Form.Item>
            <Form.Item label="备注">
              {getFieldDecorator("remark", { initialValue: editing ? editing.remark : "" })(<Input placeholder="用途说明，可选" />)}
            </Form.Item>
          </Form>
        </Modal>
      </Card>
    );
  }
}

export default connect()(Form.create()(ConfigList));
