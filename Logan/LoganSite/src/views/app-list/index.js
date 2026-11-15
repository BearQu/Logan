import React, { Component } from "react";
import { connect } from "react-redux";
import {
  Card, Table, Tag, Avatar, Button, Modal, Form, Input, Upload, message, Tooltip, Empty,
} from "antd";
import moment from "moment";
import { fetchAppListApi, saveAppApi } from "../../common/api";
import { SOURCE_META } from "../../consts/crash";
import { getCurrentAppId, setCurrentAppId } from "../../common/currentApp";

/**
 * 应用清单：多应用共用一套平台，这里看每个应用的崩溃/日志规模与登记来源。
 * 应用不需要人工录入 —— 客户端带 appId 上报日志或崩溃就自动登记；
 * 应用名与图标由客户端上报（header appName / iconBase64），也可在此手工补录覆盖。
 */
class AppList extends Component {
  state = { apps: [], loading: true, visible: false, submitting: false, editing: null, iconFile: null, iconPreview: "" };

  componentDidMount() {
    this.load();
  }

  load = async () => {
    this.setState({ loading: true });
    try {
      const apps = await fetchAppListApi();
      this.setState({ apps: apps || [], loading: false });
    } catch (e) {
      this.setState({ apps: [], loading: false });
    }
  };

  openEdit = (app) => {
    this.setState({ visible: true, editing: app, iconFile: null, iconPreview: app && app.iconPath ? "/logan-web/logan/app/icon.json?appId=" + encodeURIComponent(app.appId) : "" });
  };

  closeEdit = () => {
    const { form } = this.props;
    if (form) { form.resetFields(); }
    this.setState({ visible: false, editing: null, iconFile: null, iconPreview: "" });
  };

  pickIcon = (file) => {
    if (file.size > 512 * 1024) {
      message.error("图标请控制在 512KB 以内");
      return false;
    }
    const reader = new FileReader();
    reader.onload = () => this.setState({ iconFile: file, iconPreview: String(reader.result) });
    reader.readAsDataURL(file);
    return false;
  };

  submit = (e) => {
    e.preventDefault();
    const { form } = this.props;
    const { editing } = this.state;
    form.validateFields(async (err, values) => {
      if (err) { return; }
      this.setState({ submitting: true });
      try {
        const body = { appId: (editing && editing.appId) || values.appId, appName: values.appName, source: "manual" };
        if (values.platform) { body.platform = Number(values.platform); }
        if (this.state.iconFile) {
          const dataUrl = this.state.iconPreview || "";
          const m = /^data:([^;]+);base64,(.+)$/.exec(dataUrl);
          if (m) { body.mime = m[1]; body.iconBase64 = m[2]; }
        }
        await saveAppApi(body);
        message.success("已保存");
        this.closeEdit();
        this.setState({ submitting: false });
        this.load();
      } catch (err2) {
        this.setState({ submitting: false });
      }
    });
  };

  gotoCrash = (appId) => () => {
    setCurrentAppId(appId);
    window.location.hash = "#/crash-list?appId=" + encodeURIComponent(appId);
  };

  gotoLogs = (appId) => () => {
    setCurrentAppId(appId);
    window.location.hash = "#/native-list";
  };

  render() {
    const { apps, loading, visible, submitting, editing } = this.state;
    const { form } = this.props;
    const total = (apps || []).length;
    return (
      <div style={{ padding: 24, overflowY: "auto", height: "100%", background: "#f5f6f8" }}>
        <div style={{ display: "flex", alignItems: "center", marginBottom: 16 }}>
          <span style={{ fontSize: 20, fontWeight: 700, color: "#262626" }}>应用清单</span>
          <span style={{ marginLeft: 12, color: "#8c8c8c", fontSize: 13 }}>共 {total} 个应用 · 客户端上报即自动登记，无需手工录入</span>
          <span style={{ marginLeft: "auto" }}>
            <Button icon="plus" onClick={() => this.openEdit(null)} style={{ marginRight: 8 }}>手工登记</Button>
            <Button icon="reload" onClick={this.load} loading={loading}>刷新</Button>
          </span>
        </div>

        <Card bodyStyle={{ padding: 0 }} style={{ borderRadius: 8 }}>
          <Table
            rowKey="appId"
            size="middle"
            loading={loading}
            dataSource={apps || []}
            pagination={false}
            locale={{ emptyText: <Empty description="还没有应用。客户端用同一个 appId 上报日志或崩溃后会自动出现在这里。" /> }}
            columns={[
              {
                title: "应用", width: 260,
                render: (r) => (
                  <div style={{ display: "flex", alignItems: "center" }}>
                    {r.iconPath ? (
                      <Avatar shape="square" size={36} src={"/logan-web/logan/app/icon.json?appId=" + encodeURIComponent(r.appId)} style={{ marginRight: 10 }} />
                    ) : (
                      <Avatar shape="square" size={36} style={{ marginRight: 10, background: "#2f54eb", fontSize: 16 }}>
                        {(r.appName || r.appId || "?").slice(0, 1).toUpperCase()}
                      </Avatar>
                    )}
                    <div>
                      <div style={{ fontWeight: 600, color: "#262626" }}>{r.appName || <span style={{ color: "#8c8c8c" }}>未上报应用名</span>}</div>
                      <div style={{ fontFamily: "Menlo, monospace", fontSize: 12, color: "#8c8c8c" }}>{r.appId}</div>
                    </div>
                  </div>
                ),
              },
              {
                title: "来源", dataIndex: "source", width: 130,
                render: (v) => {
                  const m = SOURCE_META[v] || { text: v || "-", color: "default" };
                  return <Tooltip title={v === "manual" ? "人工登记" : "客户端上报自动登记"}><Tag color={m.color}>{m.text}</Tag></Tooltip>;
                },
              },
              { title: "平台", width: 80, render: (r) => r.platform === 2 ? <Tag color="geekblue">iOS</Tag> : r.platform === 1 ? <Tag color="green">Android</Tag> : <Tag>未知</Tag> },
              { title: "最新版本", width: 130, render: (r) => r.latestAppVersion ? <span>{r.latestAppVersion} ({r.latestBuildVersion || "-"})</span> : <span style={{ color: "#bfbfbf" }}>-</span> },
              {
                title: "崩溃", width: 150,
                render: (r) => (
                  <span>
                    <a style={{ color: r.crashCount ? "#f5222d" : "#bfbfbf", fontWeight: r.crashCount ? 600 : 400 }} onClick={this.gotoCrash(r.appId)}>
                      {r.crashCount || 0} 次 / {r.issueCount || 0} 问题
                    </a>
                  </span>
                ),
              },
              {
                title: "日志", width: 110,
                render: (r) => <a onClick={this.gotoLogs(r.appId)}>{r.logCount || 0} 份</a>,
              },
              { title: "设备数", dataIndex: "deviceCount", width: 90, render: (v) => v || 0 },
              {
                title: "最近上报", width: 170,
                render: (r) => r.lastReportTime ? moment(r.lastReportTime).format("YYYY-MM-DD HH:mm:ss") : <span style={{ color: "#bfbfbf" }}>-</span>,
              },
              {
                title: "操作", width: 100,
                render: (r) => <Button size="small" icon="edit" onClick={() => this.openEdit(r)}>编辑</Button>,
              },
            ]}
          />
        </Card>

        <Modal title={editing ? "编辑应用信息 · " + editing.appId : "手工登记应用"} visible={visible} onCancel={this.closeEdit} onOk={this.submit} confirmLoading={submitting} okText="保存" width={520}>
          <Form layout="vertical" onSubmit={this.submit}>
            {!editing ? (
              <Form.Item label="包名 appId">{form.getFieldDecorator("appId", { rules: [{ required: true, message: "请填写包名" }] })(<Input placeholder="com.example.app" />)}</Form.Item>
            ) : null}
            <Form.Item label="应用名称">{form.getFieldDecorator("appName", { initialValue: editing ? editing.appName : undefined })(<Input placeholder="客户端未上报时可在此补录" />)}</Form.Item>
            <Form.Item label="平台">{form.getFieldDecorator("platform", { initialValue: editing && editing.platform ? editing.platform : 1 })(
              <Input placeholder="1=Android 2=iOS" />
            )}</Form.Item>
            <Form.Item label="图标（png/jpg，≤512KB）">
              <Upload beforeUpload={this.pickIcon} showUploadList={false} accept="image/*">
                <Button icon="upload">选择图标</Button>
              </Upload>
              {this.state.iconPreview ? <img src={this.state.iconPreview} alt="icon" style={{ display: "block", marginTop: 10, width: 56, height: 56, objectFit: "contain", border: "1px solid #f0f0f0", borderRadius: 4 }} /> : null}
              <div style={{ color: "#8c8c8c", fontSize: 12, marginTop: 6 }}>通常由客户端随日志上报（iconBase64），此处仅用于补录或覆盖。</div>
            </Form.Item>
          </Form>
        </Modal>
      </div>
    );
  }
}

export default connect()(Form.create()(AppList));
