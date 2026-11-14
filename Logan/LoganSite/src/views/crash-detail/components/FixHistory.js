import React, { PureComponent } from "react";
import { Card, Icon, Tag, Button, Modal, Form, Input, Select, DatePicker, message, Empty, Timeline, Tooltip } from "antd";
import moment from "moment";
import { fetchCrashFixesApi, reportCrashFixApi } from "../../../common/api";
import { FIX_STATUS_META, parseJsonArrayMaybe } from "../../../consts/crash";

const FormItem = Form.Item;
const { TextArea } = Input;

/**
 * 历史修复：平台不碰源码，修复动作在本地 DSH 完成，这里只呈现「谁在什么时候改了什么、改成什么状态」。
 * 同一指纹再次上报崩溃时后端会自动重开并把这些记录带进 lastFixNote。
 */
class FixHistoryInner extends PureComponent {
  state = { fixes: [], loading: false, visible: false, submitting: false };

  componentDidMount() {
    this.load();
  }

  componentDidUpdate(prev) {
    if (prev.reloadNonce !== this.props.reloadNonce || (prev.issue && this.props.issue && prev.issue.id !== this.props.issue.id)) {
      this.load();
    }
  }

  load = async () => {
    const { issue } = this.props;
    if (!issue) { return; }
    this.setState({ loading: true });
    try {
      const fixes = await fetchCrashFixesApi({ appId: issue.appId, fingerprint: issue.fingerprint, limit: 20 });
      this.setState({ fixes: fixes || [], loading: false });
    } catch (e) {
      this.setState({ fixes: [], loading: false });
    }
  };

  openReport = () => this.setState({ visible: true });
  closeReport = () => this.setState({ visible: false });

  submit = (e) => {
    e.preventDefault();
    const { issue, form, onReported } = this.props;
    form.validateFields(async (err, values) => {
      if (err) { return; }
      this.setState({ submitting: true });
      try {
        const body = {
          appId: issue.appId,
          fingerprint: issue.fingerprint,
          kind: issue.kind,
          issueId: issue.id,
          fixer: values.fixer,
          action: values.action,
          status: values.action,
          rootCause: values.rootCause || undefined,
          detail: values.detail,
          files: values.files || undefined,
          commitUrl: values.commitUrl || undefined,
          prUrl: values.prUrl || undefined,
          source: "console",
          fixedAt: values.fixedAt ? values.fixedAt.valueOf() : undefined,
        };
        const res = await reportCrashFixApi(body);
        if (res && res.duplicate) {
          message.warning("同样的修复报告已存在，未重复记录");
        } else {
          message.success("修复报告已上报");
        }
        form.resetFields();
        this.setState({ visible: false, submitting: false });
        this.load();
        if (onReported) { onReported(); }
      } catch (err2) {
        this.setState({ submitting: false });
      }
    });
  };

  render() {
    const { issue, form } = this.props;
    const { fixes, loading, visible, submitting } = this.state;
    if (!issue) { return null; }
    return (
      <Card
        title={<span><Icon type="tool" style={{ color: "#fa8c16", marginRight: 8 }} />历史修复</span>}
        bodyStyle={{ padding: fixes.length ? "14px 18px" : "18px", maxHeight: 420, overflow: "auto" }}
        extra={<Button size="small" icon="plus" onClick={this.openReport}>补录修复</Button>}
        style={{ marginTop: 16 }}
        loading={loading}
      >
        {fixes.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={<span style={{ color: "#8c8c8c" }}>尚无修复记录。本地用 DSH 分析证据包改完代码后，由 skill 自动回传，或点「补录修复」手工填写。</span>}
          />
        ) : (
          <Timeline>
            {fixes.map((f) => {
              const meta = FIX_STATUS_META[f.status || f.action] || { text: f.status || f.action, color: "#8c8c8c" };
              const files = parseJsonArrayMaybe(f.files);
              return (
                <Timeline.Item key={f.id} color={meta.color === "#52c41a" ? "green" : "blue"}>
                  <div>
                    <Tag color={meta.color}>{meta.text}</Tag>
                    <b style={{ marginLeft: 4 }}>{f.fixer}</b>
                    <span style={{ color: "#8c8c8c", fontSize: 12, marginLeft: 8 }}>
                      {moment(f.fixedAt || f.addTime).format("YYYY-MM-DD HH:mm")}
                    </span>
                    {f.client ? <Tooltip title={f.client}><Tag style={{ marginLeft: 6 }} color="geekblue">{f.client}</Tag></Tooltip> : null}
                    {f.evidenceHash ? (
                      <Tooltip title={"证据包指纹 " + f.evidenceHash}>
                        <Tag style={{ marginLeft: 4 }} color="default">证据一致</Tag>
                      </Tooltip>
                    ) : null}
                  </div>
                  {f.rootCause ? <div style={{ marginTop: 6, color: "#434343", fontSize: 13 }}><b>根因：</b>{f.rootCause}</div> : null}
                  <div style={{ marginTop: 4, color: "#595959", fontSize: 13, whiteSpace: "pre-wrap" }}>{f.detail}</div>
                  {files.length ? (
                    <div style={{ marginTop: 6, fontFamily: "Menlo, monospace", fontSize: 12, color: "#722ed1" }}>
                      {files.map((x, i) => <div key={i}>· {String(x)}</div>)}
                    </div>
                  ) : null}
                  <div style={{ marginTop: 6 }}>
                    {f.commitUrl ? <a href={f.commitUrl} target="_blank" rel="noreferrer"><Icon type="branches" /> 提交</a> : null}
                    {f.prUrl ? <a href={f.prUrl} target="_blank" rel="noreferrer" style={{ marginLeft: f.commitUrl ? 10 : 0 }}><Icon type="pull-request" /> PR</a> : null}
                  </div>
                </Timeline.Item>
              );
            })}
          </Timeline>
        )}

        <Modal title="补录修复报告" visible={visible} onCancel={this.closeReport} onOk={this.submit} confirmLoading={submitting} okText="上报" width={620}>
          <Form layout="vertical" onSubmit={this.submit}>
            <FormItem label="修改人">{form.getFieldDecorator("fixer", { rules: [{ required: true, message: "请填写修改人" }] })(<Input placeholder="姓名 / 工号" />)}</FormItem>
            <FormItem label="修复结论">{form.getFieldDecorator("action", { initialValue: "fixed" })(
              <Select>
                {Object.keys(FIX_STATUS_META).filter((k) => k !== "open").map((k) => (
                  <Select.Option key={k} value={k}>{FIX_STATUS_META[k].text}</Select.Option>
                ))}
              </Select>
            )}</FormItem>
            <FormItem label="根因分析">{form.getFieldDecorator("rootCause")(<TextArea rows={2} placeholder="一句话根因，会喂给 AI 做复发判断" />)}</FormItem>
            <FormItem label="修改细节">{form.getFieldDecorator("detail", { rules: [{ required: true, message: "请填写修改细节" }] })(<TextArea rows={3} placeholder="改了什么、为什么这样改、如何验证" />)}</FormItem>
            <FormItem label="涉及文件（逗号分隔 文件名:行号）">{form.getFieldDecorator("files")(<Input placeholder="HttpDns.java:88, DnsHelper.kt:23" />)}</FormItem>
            <FormItem label="提交链接">{form.getFieldDecorator("commitUrl")(<Input placeholder="https://…/commit/…" />)}</FormItem>
            <FormItem label="修复时间">{form.getFieldDecorator("fixedAt", { initialValue: moment() })(<DatePicker showTime format="YYYY-MM-DD HH:mm" style={{ width: "100%" }} />)}</FormItem>
          </Form>
        </Modal>
      </Card>
    );
  }
}

export default Form.create()(FixHistoryInner);
