import React, { PureComponent } from "react";
import { Card, Icon, Tag, Spin, Empty, Button, Radio, message } from "antd";
import moment from "moment";
import { fetchCrashTimelineApi, fetchHttpAroundApi, fetchBehaviorAroundApi } from "../../../common/api";

const CHANNEL_META = {
  native: { label: "Native", color: "blue" },
  h5: { label: "H5", color: "purple" },
  http: { label: "网络", color: "cyan" },
  behavior: { label: "行为", color: "geekblue" },
};

const KIND_ICON = { launch: "🚀", page: "📄", route: "🧭", click: "👆", tap: "👆", submit: "⌨️", dialog: "💬", custom: "✦", foreground: "🔆", background: "🌙", scroll: "🌀" };

/**
 * 崩溃 ↔ 日志互证：拉同设备崩溃前 before 秒、后 after 秒的业务日志（native + h5 混排），
 * 让「AI 摘要脱离代码」的另一半证据补齐 —— 崩溃发生时业务上正在做什么。
 */
export default class CrashTimeline extends PureComponent {

  state = { logs: null, loading: false, channel: "all", withHttp: true, withBehavior: true };

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
    const before = 180;
    const after = 15;
    try {
      const res = await fetchCrashTimelineApi({
        appId: issue.appId,
        deviceId,
        crashTime: issue.lastCrashTime,
        before: before,
        after: after,
        limit: 200,
      });
      let logs = (res && res.logs) || [];
      // 网络事件混进同一条时间轴互证：崩溃紧跟在哪个请求之后发生，往往就是根因线索
      if (this.state.withHttp) {
        try {
          const rows = await fetchHttpAroundApi({
            appId: issue.appId,
            deviceId: deviceId,
            beginTime: Number(issue.lastCrashTime) - before * 1000,
            endTime: Number(issue.lastCrashTime) + after * 1000,
            limit: 120,
          });
          logs = logs.concat(this.mapHttpRows(rows || [], Number(issue.lastCrashTime)));
        } catch (e) { /* 没有网络数据时不影响业务日志展示 */ }
      }
      // 用户行为轨迹混入（三端 SDK 本地采集，不依赖业务接入）：崩溃前用户正在做什么——
      // 启动、进了哪些页、点了什么、有没有打开过弹窗，是把堆栈对上业务动作的关键上下文。
      if (this.state.withBehavior) {
        try {
          const res2 = await fetchBehaviorAroundApi({
            appId: issue.appId,
            deviceId: deviceId,
            crashTime: Number(issue.lastCrashTime),
            beforeSeconds: before,
            afterSeconds: after,
            limit: 120,
          });
          // axios 拦截器已解 LoganResponse 封套（见 common/api.js onRequestFulfilled），这里直接拿 {rows,total}
          logs = logs.concat(this.mapBehaviorRows((res2 && res2.rows) || [], Number(issue.lastCrashTime)));
        } catch (e) { /* 该设备没有行为数据时不影响其他通道展示 */ }
      }
      logs.sort(function (a, b) { return Number(a.deltaMs) - Number(b.deltaMs); });
      this.setState({ logs: logs, loading: false });
    } catch (e) {
      this.setState({ logs: [], loading: false });
    }
  };

  mapHttpRows(rows, crashTime) {
    return (rows || []).map(function (r) {
      var parts = [];
      parts.push(String(r.method || "GET"));
      parts.push(String(r.host || "") + String(r.path || ""));
      if (r.status) { parts.push("→ " + r.status); }
      if (r.errKind) { parts.push("[" + r.errKind + "]"); }
      if (r.errMsg) { parts.push(" " + String(r.errMsg).slice(0, 120)); }
      if (r.durationMs) { parts.push(" (" + r.durationMs + "ms)"); }
      if (r.kind === "h5" && r.pageUrl) { parts.push(" 页面:" + String(r.pageUrl).slice(0, 80)); }
      var delta = Number(r.occurredAt || 0) - crashTime;
      return {
        detailId: "h-" + r.id,
        channel: "http",
        content: parts.join(" "),
        deltaMs: delta,
        nearCrash: delta <= 0 && delta >= -10000,
        truncated: false,
      };
    });
  }

  mapBehaviorRows(rows, crashTime) {
    return (rows || []).map(function (r) {
      var parts = [];
      var kind = String(r.kind || "custom");
      parts.push((KIND_ICON[kind] || "") + " " + kind);
      if (r.name) { parts.push(String(r.name).slice(0, 120)); }
      if (r.label) { parts.push("·" + String(r.label).slice(0, 80)); }
      if (r.pageUrl) { parts.push(" " + String(r.pageUrl).slice(0, 100)); }
      var delta = Number(r.occurredAt || 0) - crashTime;
      return {
        detailId: "b-" + r.id,
        channel: "behavior",
        content: parts.join(" "),
        deltaMs: delta,
        nearCrash: delta <= 0 && delta >= -10000,
        truncated: false,
      };
    });
  }

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
              <Radio.Button value="http">网络</Radio.Button>
              <Radio.Button value="behavior">行为</Radio.Button>
            </Radio.Group>
            <Button size="small" type={this.state.withHttp ? "primary" : "default"} icon="global" style={{ marginLeft: 8 }}
              onClick={() => this.setState({ withHttp: !this.state.withHttp }, this.load)}
            >混入网络事件</Button>
            <Button size="small" type={this.state.withBehavior ? "primary" : "default"} icon="fund" style={{ marginLeft: 8 }}
              onClick={() => this.setState({ withBehavior: !this.state.withBehavior }, this.load)}
            >混入行为轨迹</Button>
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
