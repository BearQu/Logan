import React, { Component } from "react";
import { connect } from "react-redux";
import {
  Card, Table, Tag, Avatar, Button, Modal, Form, Input, Upload, message, Tooltip, Empty,
  Select, Alert,
} from "antd";
import moment from "moment";
import { fetchAppListApi, saveAppApi, createAppApi } from "../../common/api";
import { SOURCE_META } from "../../consts/crash";
import { getCurrentAppId, setCurrentAppId } from "../../common/currentApp";

/**
 * 应用清单：多应用共用一套平台，这里看每个应用的崩溃/日志规模与登记来源。
 * 应用不需要人工录入 —— 客户端带 appId 上报日志或崩溃就自动登记；
 * 应用名与图标由客户端上报（header appName / iconBase64），也可在此手工补录覆盖。
 */
var SDK_HINT = [
  "SDK 侧配置（建好后填这一个值）",
  "Android    LoganKit.APP_ID        = \u0022...\u0022",
  "iOS        LoganKit.m kLoganAppId = @\u0022...\u0022",
  "HarmonyOS  LoganKit.ets APP_ID    = '...'",
  "上报通道自动携带：header appId + /logan/upload.json、/logan/crash/upload.json"
].join(String.fromCharCode(10));

class AppList extends Component {
  state = { apps: [], loading: true, visible: false, submitting: false, editing: null, iconFile: null, iconPreview: "", creating: false, createSubmitting: false, created: null };

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
        const appId = (editing && editing.appId) || String(values.appId || "").trim();
        const body = { appId: appId, appName: values.appName, source: "manual" };
        if (values.platform) { body.platform = Number(values.platform); }
        if (this.state.iconFile) {
          const dataUrl = this.state.iconPreview || "";
          const m = /^data:([^;]+);base64,(.+)$/.exec(dataUrl);
          if (m) { body.mime = m[1]; body.iconBase64 = m[2]; }
        }
        // 治理规则：新建只能走 create.json（save.json 只更新已登记应用），补图标再走 save.json
        if (!editing) {
          if (!body.platform) { throw new Error("请选择所属端"); }
          await createAppApi({ appId: appId, appName: values.appName, platform: Number(body.platform) });
          if (body.iconBase64) {
            await saveAppApi({ appId: appId, source: "manual", mime: body.mime, iconBase64: body.iconBase64 });
          }
        } else {
          await saveAppApi(body);
        }
        message.success("已保存");
        this.closeEdit();
        this.setState({ submitting: false });
        this.load();
      } catch (err2) {
        this.setState({ submitting: false });
      }
    });
  };

  openCreate = () => {
    const { form } = this.props;
    if (form) { form.resetFields(["create_appId", "create_appName", "create_platform"]); }
    this.setState({ creating: true, created: null });
  };

  closeCreate = () => {
    const { form } = this.props;
    if (form) { form.resetFields(["create_appId", "create_appName", "create_platform"]); }
    this.setState({ creating: false, created: null });
  };

  fillCreateAppId = () => {
    const { form } = this.props;
    const base = (form.getFieldValue("create_appId") || "").replace(/\.(android|ios|harmony)$/, "");
    const plat = Number(form.getFieldValue("create_platform") || 1);
    const suffix = plat === 2 ? ".ios" : plat === 3 ? ".harmony" : ".android";
    form.setFieldsValue({ create_appId: base + suffix });
  };

  submitCreate = (e) => {
    e.preventDefault();
    const { form } = this.props;
    form.validateFields(["create_appId", "create_appName", "create_platform"], async (err, values) => {
      if (err) { return; }
      this.setState({ createSubmitting: true });
      try {
        const dto = await createAppApi({
          appId: String(values.create_appId || "").trim(),
          appName: String(values.create_appName || "").trim(),
          platform: Number(values.create_platform),
        });
        this.setState({ created: dto || null, createSubmitting: false });
        message.success("应用已创建");
        this.load();
      } catch (e2) {
        this.setState({ createSubmitting: false });
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
    const { apps, loading, visible, submitting, editing, creating, created, createSubmitting } = this.state;
    const { form } = this.props;
    const total = (apps || []).length;
    return (
      <div style={{ padding: 24, overflowY: "auto", height: "100%", background: "#f5f6f8" }}>
        <div style={{ display: "flex", alignItems: "center", marginBottom: 16 }}>
          <span style={{ fontSize: 20, fontWeight: 700, color: "#262626" }}>应用清单</span>
          <span style={{ marginLeft: 12, color: "#8c8c8c", fontSize: 13 }}>共 {total} 个应用 · 一个端一个应用 · 客户端带 appId 上报即入库，未登记会标记提醒</span>
          <span style={{ marginLeft: "auto" }}>
            <Button type="primary" icon="plus" onClick={this.openCreate} style={{ marginRight: 8 }}>创建应用</Button>
            <Tooltip title="客户端还没接入 SDK 时补录用"><Button icon="plus" onClick={() => this.openEdit(null)} style={{ marginRight: 8 }}>手工补录</Button></Tooltip>
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
                  if (v === "manual") {
                    return <Tooltip title="已在平台正式登记（创建应用或人工补录）"><Tag color="green">平台已登记</Tag></Tooltip>;
                  }
                  return (
                    <Tooltip title="客户端上报时自动入库的，还没在平台正式登记 —— 建议点「创建应用」补登记，并在 SDK 里使用各端独立 appId">
                      <Tag color="orange">未登记</Tag>
                    </Tooltip>
                  );
                },
              },
              { title: "平台", width: 90, render: (r) => r.platform === 2 ? <Tag color="geekblue">iOS</Tag> : r.platform === 1 ? <Tag color="green">Android</Tag> : r.platform === 3 ? <Tag color="purple">HarmonyOS</Tag> : <Tag>未知</Tag> },
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

        <Modal title="创建应用（一个端一个 appId）" visible={creating} onCancel={this.closeCreate} onOk={this.submitCreate} confirmLoading={createSubmitting} okText="创建" width={620}>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="同一产品的 Android / iOS / HarmonyOS 各自建一个应用，appId 互不共用；客户端 SDK 上报哪个 appId，数据就落到哪个应用。"
            description="推荐 appId 形式：包名 + 端后缀，例如 com.example.app.android / .ios / .harmony。未登记的 appId 照常入库不丢数据，清单里会标「未登记」提醒你补建。"
          />
          <Form layout="vertical" onSubmit={this.submitCreate}>
            <Form.Item label="所属端" required>
              {form.getFieldDecorator("create_platform", { initialValue: 1 })(
                <Select onChange={() => this.fillCreateAppId()}>
                  <Select.Option value={1}>Android</Select.Option>
                  <Select.Option value={2}>iOS</Select.Option>
                  <Select.Option value={3}>HarmonyOS</Select.Option>
                </Select>
              )}
            </Form.Item>
            <Form.Item label="应用名称" required>
              {form.getFieldDecorator("create_appName", { rules: [{ required: true, message: "请填写应用名称" }] })(<Input placeholder="如：保贝（Android）" />)}
            </Form.Item>
            <Form.Item
              label="appId（唯一，创建后不可改）"
              required
              extra="客户端 SDK 的 LoganKit 里配置成同一个值；换端就再建一个，别共用。"
            >
              {form.getFieldDecorator("create_appId", { rules: [{ required: true, message: "请填写 appId" }, { pattern: /^[A-Za-z0-9_.\-]{3,128}$/, message: "只允许字母、数字、点、横线、下划线（3-128 位）" }] })(
                <Input placeholder="com.example.app.android" />
              )}
            </Form.Item>
            <div style={{ background: "#f6f8fa", border: "1px solid #e8e8e8", borderRadius: 6, padding: "10px 12px", fontFamily: "Menlo, monospace", fontSize: 12, whiteSpace: "pre-wrap", color: "#595959" }}>
              <div style={{ whiteSpace: "pre-wrap" }}>{SDK_HINT}</div>
            </div>
            {created ? (
              <Alert
                type="success"
                showIcon
                style={{ marginTop: 16 }}
                message={"已创建：" + created.appName + " · " + created.appId}
                description={"把 SDK 的 appId 配置成 " + created.appId + " 后重新打包，上报即归属该应用。"}
              />
            ) : null}
          </Form>
        </Modal>

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
