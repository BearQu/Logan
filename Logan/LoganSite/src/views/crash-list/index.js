import React, { Component } from "react";
import { connect } from "react-redux";
import { parse } from "qs";
import moment from "moment";
import {
  Card, Row, Col, Table, Tag, Select, Input, Button, Icon, Tooltip, Badge, message, Modal,
} from "antd";
import {
  fetchCrashIssuesApi, fetchCrashStatsApi, fetchCrashTrendApi, updateCrashIssueStatusApi,
} from "../../common/api";
import { KIND_META, SEVERITY_META, STATUS_META, parseJsonArrayMaybe } from "../../consts/crash";
import TrendChart from "./components/TrendChart";

const { Option } = Select;
const { confirm } = Modal;

/**
 * 崩溃概览（仿 Bugly 控制台）：KPI 卡片 + 按天趋势 + 聚合问题列表。
 * 数据全部来自 Server 的 /logan/crash/* 查询 API。
 */
export class CrashList extends Component {

  state = {
    appId: "",
    kind: "",
    statusFilter: "",
    keyword: "",
    issues: [],
    total: 0,
    stats: null,
    trend: [],
    loading: false,
  };

  componentDidMount() {
    let params = {};
    if (this.props.location) {
      params = parse(this.props.location.search, { ignoreQueryPrefix: true });
    }
    const appId = params.appId || "com.ccic.baodai";
    this.setState({ appId }, () => this.loadAll());
  }

  loadAll = async () => {
    const { appId } = this.state;
    this.setState({ loading: true });
    try {
      const [issuesData, stats, trend] = await Promise.all([
        fetchCrashIssuesApi(appId, 100),
        fetchCrashStatsApi(appId),
        fetchCrashTrendApi(appId, null, null),
      ]);
      this.setState({
        issues: (issuesData && issuesData.issues) || [],
        total: (issuesData && issuesData.total) || 0,
        stats: stats || null,
        trend: trend || [],
      });
    } catch (e) {
      // 拦截器已弹错误提示
    } finally {
      this.setState({ loading: false });
    }
  };

  openDetail = (record) => {
    window.location.hash = `#/crash-detail?id=${record.id}&appId=${encodeURIComponent(this.state.appId)}`;
  };

  changeStatus = (record, status) => {
    confirm({
      title: `将问题标记为「${STATUS_META[status].label}」？`,
      content: `指纹 ${record.fingerprint}`,
      onOk: async () => {
        await updateCrashIssueStatusApi(record.id, status);
        message.success("已更新状态");
        this.loadAll();
      },
    });
  };

  severityCell = (sev) => {
    if (!sev) return <Tag>待分析</Tag>;
    const meta = SEVERITY_META[sev] || { color: "#999", bg: "#fafafa" };
    return (
      <span style={{ color: meta.color, background: meta.bg, padding: "1px 8px", borderRadius: 3, fontSize: 12, fontWeight: 600 }}>
        {sev}
      </span>
    );
  };

  renderKpi() {
    const { stats, total } = this.state;
    const kinds = (stats && stats.kinds) || [];
    const card = (title, value, sub, color, icon) => (
      <Card bodyStyle={{ padding: "16px 20px" }} style={styles.kpiCard}>
        <div style={{ display: "flex", alignItems: "center" }}>
          <div style={{ ...styles.kpiIcon, background: color }}>
            <Icon type={icon} style={{ color: "#fff", fontSize: 22 }} />
          </div>
          <div style={{ marginLeft: 14 }}>
            <div style={{ color: "#8c8c8c", fontSize: 13 }}>{title}</div>
            <div style={{ fontSize: 26, fontWeight: 700, lineHeight: "32px", color: "#262626" }}>{value}</div>
            {sub && <div style={{ fontSize: 12, color: "#bfbfbf" }}>{sub}</div>}
          </div>
        </div>
      </Card>
    );
    const kindTotal = (k) => {
      const row = kinds.find(r => r.kind === k);
      return row ? row.total : 0;
    };
    return (
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>{card("崩溃上报总数", total || 0, undefined, "#f5222d", "bug")}</Col>
        <Col span={6}>{card("Java 崩溃", kindTotal("java"), undefined, "#2f54eb", "code")}</Col>
        <Col span={6}>{card("Native 崩溃", kindTotal("native"), undefined, "#fa541c", "tool")}</Col>
        <Col span={6}>{card("ANR", kindTotal("anr"), `未处理问题 ${(stats && stats.unresolvedIssues) || 0}`, "#faad14", "clock-circle")}</Col>
      </Row>
    );
  }

  filteredIssues() {
    const { issues, kind, statusFilter, keyword } = this.state;
    return issues.filter(i => {
      if (kind && i.kind !== kind) return false;
      if (statusFilter !== "" && String(i.status) !== statusFilter) return false;
      if (keyword) {
        const kw = keyword.toLowerCase();
        const real = [`${i.signalInfo || ""}`, `${i.fingerprint || ""}`, `${i.llmSummary || ""}`, `${(i.sampleContent || "").slice(0, 2000)}`];
        if (!real.some(t => t.toLowerCase().indexOf(kw) >= 0)) return false;
      }
      return true;
    });
  }

  columns = [
    {
      title: "严重性", dataIndex: "llmSeverity", width: 84,
      render: this.severityCell,
    },
    {
      title: "崩溃摘要", dataIndex: "llmSummary", ellipsis: true,
      render: (text, record) => (
        <div>
          <div
            style={{ color: "#1890ff", cursor: "pointer", fontWeight: 500 }}
            onClick={() => this.openDetail(record)}
          >
            {record.signalInfo || record.fingerprint}
          </div>
          <div style={{ color: "#8c8c8c", fontSize: 12, marginTop: 2, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: 620 }}>
            {text || "LLM 分析中，稍后自动生成…"}
          </div>
        </div>
      ),
    },
    {
      title: "类型", dataIndex: "kind", width: 84,
      render: (k) => {
        const meta = KIND_META[k] || { label: k, color: "#999" };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: "次数", dataIndex: "crashCount", width: 70,
      render: (v) => <span style={{ fontWeight: 600, color: v > 0 ? "#f5222d" : "#262626" }}>{v}</span>,
    },
    {
      title: "影响设备", dataIndex: "affectedDevices", width: 90,
      render: (v) => {
        const devs = parseJsonArrayMaybe(v);
        return devs.length ? (
          <Tooltip title={devs.join("\n")}>
            <span>{devs.length} 台</span>
          </Tooltip>
        ) : "-";
      },
    },
    {
      title: "首次 / 最近发生", dataIndex: "lastCrashTime", width: 190,
      render: (v, r) => (
        <span style={{ fontSize: 12, color: "#595959" }}>
          {r.firstCrashTime ? moment(r.firstCrashTime).format("MM-DD HH:mm") : "-"}
          {"  /  "}
          <b>{v ? moment(v).format("MM-DD HH:mm") : "-"}</b>
        </span>
      ),
    },
    {
      title: "状态", dataIndex: "status", width: 90,
      render: (s, record) => {
        const meta = STATUS_META[s] || STATUS_META[0];
        return (
          <Select
            size="small"
            value={s}
            style={{ width: 84 }}
            onChange={(v) => this.changeStatus(record, v)}
          >
            {Object.keys(STATUS_META).map(k => (
              <Option key={k} value={Number(k)}>{STATUS_META[k].label}</Option>
            ))}
          </Select>
        );
      },
    },
    {
      title: "操作", width: 90,
      render: (v, record) => (
        <Button size="small" type="primary" ghost onClick={() => this.openDetail(record)}>详情</Button>
      ),
    },
  ];

  render() {
    const { loading, appId, kind, statusFilter, keyword } = this.state;
    return (
      <div style={{ padding: 24, overflowY: "auto", height: "100%", background: "#f5f6f8" }}>
        <div style={{ display: "flex", alignItems: "center", marginBottom: 16 }}>
          <span style={{ fontSize: 20, fontWeight: 700, color: "#262626" }}>崩溃分析</span>
          <span style={{ marginLeft: 12, color: "#8c8c8c", fontSize: 13 }}>appId: {appId || "-"}</span>
          <Button icon="reload" style={{ marginLeft: "auto" }} onClick={this.loadAll} loading={loading}>刷新</Button>
        </div>

        {this.renderKpi()}

        <Card title="近 14 天趋势" bodyStyle={{ padding: "12px 16px" }} style={{ marginBottom: 16 }}>
          <TrendChart data={this.state.trend} />
        </Card>

        <Card
          title="聚合问题"
          extra={
            <div style={{ display: "flex", gap: 8 }}>
              <Select style={{ width: 110 }} value={kind} onChange={v => this.setState({ kind: v })}>
                <Option value="">全部类型</Option>
                <Option value="java">Java</Option>
                <Option value="native">Native</Option>
                <Option value="anr">ANR</Option>
              </Select>
              <Select style={{ width: 110 }} value={statusFilter} onChange={v => this.setState({ statusFilter: v })}>
                <Option value="">全部状态</Option>
                <Option value="0">未处理</Option>
                <Option value="1">处理中</Option>
                <Option value="2">已修复</Option>
                <Option value="3">忽略</Option>
              </Select>
              <Input.Search
                placeholder="搜索 摘要/指纹/异常"
                style={{ width: 220 }}
                allowClear
                defaultValue={keyword}
                onSearch={v => this.setState({ keyword: v || "" })}
              />
            </div>
          }
        >
          <Table
            rowKey="id"
            size="middle"
            loading={loading}
            dataSource={this.filteredIssues()}
            columns={this.columns}
            pagination={{ pageSize: 15, showTotal: t => `共 ${t} 个问题` }}
          />
        </Card>
      </div>
    );
  }
}

const styles = {
  kpiCard: { borderRadius: 8 },
  kpiIcon: { width: 48, height: 48, borderRadius: 8, display: "flex", alignItems: "center", justifyContent: "center" },
};

export default connect()(CrashList);
