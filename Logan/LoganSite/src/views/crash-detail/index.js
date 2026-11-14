import React, { Component } from "react";
import { connect } from "react-redux";
import { parse } from "qs";
import moment from "moment";
import {
  Card, Tag, Table, Button, Icon, Row, Col, Descriptions, Modal, message, Spin,
} from "antd";
import {
  fetchCrashIssueDetailApi, fetchCrashInstancesApi, fetchSingleCrashApi, updateCrashIssueStatusApi,
} from "../../common/api";
import { KIND_META, SEVERITY_META, STATUS_META, parseJsonArrayMaybe } from "../../consts/crash";

/** 崩溃详情（仿 Bugly）：LLM 定性 + 基本信息 + 崩溃实例列表 + 堆栈正文。 */
export class CrashDetail extends Component {

  state = {
    issue: null,
    instances: [],
    loading: true,
    activeContent: null,
    activeTitle: "",
  };

  componentDidMount() {
    const params = parse((this.props.location && this.props.location.search) || "", { ignoreQueryPrefix: true });
    this.params = params;
    this.load();
  }

  load = async () => {
    const { id, appId } = this.params;
    this.setState({ loading: true });
    try {
      const issue = await fetchCrashIssueDetailApi(Number(id));
      if (!issue) { message.error("问题不存在"); return; }
      this.setState({ issue });
      const appIdFinal = appId || issue.appId;
      const instances = await fetchCrashInstancesApi(appIdFinal, issue.fingerprint, 50);
      this.setState({ instances: instances || [] });
    } catch (e) {
      // 拦截器已提示
    } finally {
      this.setState({ loading: false });
    }
  };

  back = () => { window.location.hash = "#/crash-list"; };

  showStack = async (record) => {
    if (record.content) {
      this.setState({ activeContent: record.content, activeTitle: `崩溃实例 #${record.id} · ${record.deviceId}` });
      return;
    }
    const full = await fetchSingleCrashApi(record.id);
    if (full) {
      this.setState({ activeContent: full.content, activeTitle: `崩溃实例 #${record.id} · ${record.deviceId}` });
    }
  };

  markStatus = async (status) => {
    await updateCrashIssueStatusApi(this.state.issue.id, status);
    message.success(`已标记为「${STATUS_META[status].label}」`);
    this.load();
  };

  render() {
    const { issue, instances, loading, activeContent, activeTitle } = this.state;
    if (loading && !issue) {
      return <div style={{ padding: 60, textAlign: "center" }}><Spin size="large" /></div>;
    }
    if (!issue) {
      return <div style={{ padding: 40 }}>未找到该崩溃问题 <Button onClick={this.back}>返回列表</Button></div>;
    }
    const sevMeta = SEVERITY_META[issue.llmSeverity] || { color: "#999", bg: "#fafafa" };
    const kindMeta = KIND_META[issue.kind] || { label: issue.kind, color: "#999" };
    const devices = parseJsonArrayMaybe(issue.affectedDevices);
    const frames = parseJsonArrayMaybe(issue.topFrames);
    return (
      <div style={{ padding: 24, overflowY: "auto", height: "100%", background: "#f5f6f8" }}>
        <div style={{ marginBottom: 16 }}>
          <Button icon="arrow-left" onClick={this.back} style={{ marginRight: 12 }}>返回</Button>
          <span style={{ fontSize: 18, fontWeight: 700 }}>{issue.signalInfo || issue.fingerprint}</span>
          <Tag color={kindMeta.color} style={{ marginLeft: 10 }}>{kindMeta.label}</Tag>
          <span style={{ color: sevMeta.color, background: sevMeta.bg, padding: "1px 10px", borderRadius: 3, fontSize: 13, fontWeight: 600, marginLeft: 6 }}>
            {issue.llmSeverity || "待分析"}
          </span>
          <span style={{ float: "right" }}>
            <Button size="small" onClick={() => this.markStatus(1)} style={{ marginRight: 8 }}>处理中</Button>
            <Button size="small" type="primary" onClick={() => this.markStatus(2)} style={{ marginRight: 8 }}>标记已修复</Button>
            <Button size="small" onClick={() => this.markStatus(3)}>忽略</Button>
          </span>
        </div>

        <Row gutter={16}>
          <Col span={16}>
            <Card
              title={<span><Icon type="robot" style={{ color: "#722ed1", marginRight: 8 }} />AI 智能分析</span>}
              bodyStyle={{ padding: "14px 18px" }}
              style={{ marginBottom: 16 }}
            >
              {issue.llmSummary ? (
                <div style={{ lineHeight: 1.9, color: "#434343", whiteSpace: "pre-wrap" }}>{issue.llmSummary}</div>
              ) : (
                <span style={{ color: "#8c8c8c" }}>LLM 分析排队中，约 1 分钟内自动生成。</span>
              )}
            </Card>

            <Card title="崩溃实例" bodyStyle={{ padding: 0 }} style={{ marginBottom: 16 }}>
              <Table
                rowKey="id"
                size="small"
                dataSource={instances}
                pagination={false}
                scroll={{ x: 900 }}
                columns={[
                  { title: "设备", render: (r) => `${r.brand || "-"} ${r.model || ""}`.trim(), width: 150 },
                  { title: "系统", render: (r) => r.releaseVer ? `Android ${r.releaseVer} (api ${r.sdkInt})` : "-", width: 150 },
                  { title: "版本", render: (r) => `${r.appVersion || "-"} (${r.buildVersion || "-"})`, width: 120 },
                  { title: "时间", render: (r) => r.crashTime ? moment(r.crashTime).format("YYYY-MM-DD HH:mm:ss") : "-", width: 170 },
                  { title: "补传", render: (r) => r.deferred === 1 ? <Tag color="orange">延迟补传</Tag> : <Tag color="green">实时</Tag>, width: 90 },
                  {
                    title: "堆栈", width: 100,
                    render: (r) => <Button size="small" onClick={() => this.showStack(r)}>查看</Button>,
                  },
                ]}
              />
            </Card>
          </Col>

          <Col span={8}>
            <Card title="基本信息" bodyStyle={{ padding: "12px 18px" }}>
              <Descriptions column={1} size="small" labelStyle={{ color: "#8c8c8c" }}>
                <Descriptions.Item label="指纹"><span style={{ fontFamily: "monospace" }}>{issue.fingerprint}</span></Descriptions.Item>
                <Descriptions.Item label="次数"><b style={{ color: "#f5222d" }}>{issue.crashCount}</b></Descriptions.Item>
                <Descriptions.Item label="影响设备">{devices.length} 台{devices.length ? `（${devices.slice(0, 3).join(", ")}${devices.length > 3 ? " …" : ""}）` : ""}</Descriptions.Item>
                <Descriptions.Item label="首次发现">{issue.firstCrashTime ? moment(issue.firstCrashTime).format("YYYY-MM-DD HH:mm:ss") : "-"}</Descriptions.Item>
                <Descriptions.Item label="最近发生">{issue.lastCrashTime ? moment(issue.lastCrashTime).format("YYYY-MM-DD HH:mm:ss") : "-"}</Descriptions.Item>
                <Descriptions.Item label="处理状态">
                  <Tag color={(STATUS_META[issue.status] || STATUS_META[0]).color}>{(STATUS_META[issue.status] || STATUS_META[0]).label}</Tag>
                </Descriptions.Item>
              </Descriptions>
            </Card>

            <Card title="栈顶帧" bodyStyle={{ padding: "12px 18px" }} style={{ marginTop: 16 }}>
              {frames.length === 0 && <span style={{ color: "#8c8c8c" }}>无（native 崩溃缺符号表）</span>}
              {frames.map((f, i) => (
                <div key={i} style={{ fontFamily: "Menlo, monospace", fontSize: 12, color: "#434343", padding: "4px 0", borderBottom: i < frames.length - 1 ? "1px dashed #f0f0f0" : "none", wordBreak: "break-all" }}>
                  #{String(i).padStart(2, "0")} {String(f)}
                </div>
              ))}
            </Card>
          </Col>
        </Row>

        <Modal
          title={activeTitle}
          visible={!!activeContent}
          onCancel={() => this.setState({ activeContent: null })}
          footer={null}
          width={860}
        >
          <pre style={{ background: "#1e1e1e", color: "#d4d4d4", padding: 16, borderRadius: 6, maxHeight: 520, overflow: "auto", fontSize: 12, lineHeight: 1.7 }}>
            {activeContent}
          </pre>
        </Modal>
      </div>
    );
  }
}

export default connect()(CrashDetail);
