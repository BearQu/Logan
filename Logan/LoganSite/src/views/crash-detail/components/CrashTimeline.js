import React, { PureComponent } from "react";
import { Card, Icon, Tag, Spin, Empty, Button, Radio, message } from "antd";
import moment from "moment";
import { fetchCrashTimelineApi } from "../../../common/api";

const CHANNEL_META = {
  native: { label: "Native", color: "blue" },
  h5: { label: "H5", color: "purple" },
};

/**
 * 崩溃 ↔ 日志互证：拉同设备崩溃前 before 秒、后 after 秒的业务日志（native + h5 混排），
 * 让「AI 摘要脱离代码」的另一半证据补齐 —— 崩溃发生时业务上正在做什么。
 */
export default class CrashTimeline extends PureComponent {

  state = { logs: null, loading: false, channel: "all" };

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
    const devices = (() => {
      try { return JSON.parse(issue.affectedDevices || "[]"); } catch (e) { return []; }
    })();
    const deviceId = devices.length ? devices[devices.length - 1] : null;
    if (!deviceId) {
      this.setState({ logs: [], loading: false });
      return;
    }
    this.setState({ loading: true });
    try {
      const res = await fetchCrashTimelineApi({
        appId: issue.appId,
        deviceId,
        crashTime: issue.lastCrashTime,
        before: 180,
        after: 15,
        limit: 200,
      });
      this.setState({ logs: (res && res.logs) || [], loading: false });
    } catch (e) {
      this.setState({ logs: [], loading: false });
    }
  };

  render() {
    const { issue } = this.props;
    const { logs, loading, channel } = this.state;
    const devices = (() => {
      try { return JSON.parse((issue && issue.affectedDevices) || "[]"); } catch (e) { return []; }
    })();
    const deviceId = devices.length ? devices[devices.length - 1] : null;
    const shown = logs === null ? [] : logs.filter((l) => channel === "all" || l.channel === channel);
    return (
      <Card
        title={<span><Icon type="history" style={{ color: "#13c2c2", marginRight: 8 }} />崩溃前后关联日志{deviceId ? <span style={{ color: "#8c8c8c", fontWeight: 400, fontSize: 12, marginLeft: 10 }}>设备 {deviceId}，前 3 分钟 / 后 15 秒</span> : null}</span>}
        bodyStyle={{ padding: logs && shown.length ? "8px 18px" : "18px", maxHeight: 460, overflow: "auto" }}
        extra={
          <span>
            <Radio.Group size="small" value={channel} onChange={(e) => this.setState({ channel: e.target.value })}>
              <Radio.Button value="all">全部</Radio.Button>
              <Radio.Button value="native">Native</Radio.Button>
              <Radio.Button value="h5">H5</Radio.Button>
            </Radio.Group>
            <Button size="small" icon="reload" style={{ marginLeft: 8 }} onClick={this.load} />
          </span>
        }
        style={{ marginBottom: 16 }}
      >
        {loading ? <div style={{ textAlign: "center", padding: 24 }}><Spin /></div> : null}
        {!loading && logs && shown.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={<span style={{ color: "#8c8c8c" }}>该设备崩溃时间窗内没有业务日志 —— 客户端 SDK 需同时接入 Logan 日志上传，崩溃才有业务上下文可印证</span>}
          />
        ) : null}
        {!loading && shown.length > 0 ? (
          <div>
            {shown.map((l, idx) => {
              const meta = CHANNEL_META[l.channel] || { label: l.channel, color: "default" };
              return (
                <div key={l.detailId + "-" + idx} style={{ padding: "7px 0", borderBottom: idx < shown.length - 1 ? "1px dashed #f0f0f0" : "none", display: "flex", alignItems: "flex-start" }}>
                  <span style={{ minWidth: 92, fontFamily: "Menlo, monospace", fontSize: 12, color: l.nearCrash ? "#f5222d" : "#8c8c8c", fontWeight: l.nearCrash ? 700 : 400 }}>
                    {l.deltaMs >= 0 ? "+" : ""}{(l.deltaMs / 1000).toFixed(1)}s
                  </span>
                  <Tag color={meta.color} style={{ minWidth: 52, textAlign: "center", marginRight: 8 }}>{meta.label}</Tag>
                  <span style={{ flex: 1, color: l.nearCrash ? "#262626" : "#595959", whiteSpace: "pre-wrap", wordBreak: "break-all", fontSize: 13 }}>
                    {l.content}
                    {l.truncated ? <span style={{ color: "#bfbfbf" }}> …</span> : null}
                  </span>
                </div>
              );
            })}
          </div>
        ) : null}
      </Card>
    );
  }
}
