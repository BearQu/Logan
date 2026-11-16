import React, { Component } from "react";
import { connect } from "react-redux";
import {
  Card, Table, Tag, Button, Input, Tooltip, Badge, message, Modal, Empty, Spin, Drawer, Descriptions,
} from "antd";
import moment from "moment";
import {
  fetchDeviceListApi, dispatchDeviceCommandApi, cancelDeviceCommandApi, fetchDeviceCommandsApi,
  fetchDeviceInfoApi,
} from "../../common/api";
import { getCurrentAppId, ensureApp, subscribe as subscribeApp } from "../../common/currentApp";

// 设备清单是派生视图：数据来自上报记录（logan_task + logan_crash）与客户端轮询心跳，
// 不另建注册表，避免第二套真值。这里也是「平台主动拉取某台设备日志」的唯一操作入口。

const PLATFORM_META = {
  1: { text: "Android", color: "green" },
  2: { text: "iOS", color: "blue" },
  3: { text: "HarmonyOS", color: "purple" },
};

const COMMAND_STATUS_META = {
  pending: { text: "待领取", color: "gold", tip: "等设备下次轮询领取（客户端默认 60s 一次）" },
  delivered: { text: "已下发", color: "blue", tip: "已交给客户端，等它执行完回报" },
  acked: { text: "已完成", color: "green", tip: "客户端确认执行成功" },
  failed: { text: "执行失败", color: "red", tip: "客户端回报失败" },
  cancelled: { text: "已取消", color: "default", tip: "平台侧取消" },
  expired: { text: "已过期", color: "orange", tip: "设备一直没来领取，命令超时作废" },
  lost: { text: "执行超时", color: "orange", tip: "下发后迟迟没回报，可能设备已离线" },
};

const ACTION_META = {
  upload_logs: "拉取日志",
  ping: "连通性测试",
  collect_info: "采集设备信息",
};

function fmtTime(v) {
  if (!v) return "-";
  const m = moment(Number(v));
  return m.isValid() ? m.format("MM-DD HH:mm:ss") : "-";
}

function relText(v) {
  if (!v) return "从未";
  const diff = Date.now() - Number(v);
  if (diff < 60000) return "刚刚";
  if (diff < 3600000) return Math.floor(diff / 60000) + " 分钟前";
  if (diff < 86400000) return Math.floor(diff / 3600000) + " 小时前";
  return Math.floor(diff / 86400000) + " 天前";
}

class DeviceList extends Component {
  state = {
    appId: getCurrentAppId() || "",
    devices: [],
    total: 0,
    loading: true,
    keyword: "",
    pulling: null,
    commandDevice: null,
    commands: [],
    commandLoading: false,
    confirmVisible: false,
    confirmTarget: null,
    infoDevice: null,
    infoData: null,
    infoLoading: false,
    collecting: null,
  };

  timer = null;
  unsub = null;

  componentDidMount() {
    ensureApp().then(() => {
      const id = getCurrentAppId();
      if (id !== this.state.appId) this.setState({ appId: id });
      this.load();
    });
    this.unsub = subscribeApp(() => this.setState({ appId: getCurrentAppId() || "" }, () => this.load()));
    // 命令状态靠客户端轮询推进，页面自动刷新，人不用反复点刷新
    this.timer = setInterval(() => this.load(true), 8000);
  }

  componentWillUnmount() {
    if (this.timer) clearInterval(this.timer);
    if (this.unsub) this.unsub();
  }

  load = (silent) => {
    const { appId, keyword } = this.state;
    if (!appId) {
      this.setState({ devices: [], total: 0, loading: false });
      return;
    }
    if (!silent) this.setState({ loading: true });
    fetchDeviceListApi({ appId, keyword: keyword || undefined, limit: 200, offset: 0 })
      .then((data) => {
        this.setState({
          devices: (data && data.devices) || [],
          total: (data && data.total) || 0,
          loading: false,
        });
      })
      .catch(() => this.setState({ loading: false }));
  };

  askPull = (row) => this.setState({ confirmVisible: true, confirmTarget: row });

  closeConfirm = () => this.setState({ confirmVisible: false, confirmTarget: null });

  doPull = () => {
    const row = this.state.confirmTarget;
    if (!row) return;
    this.setState({ pulling: row.deviceId });
    dispatchDeviceCommandApi({
      appId: this.state.appId,
      deviceId: row.deviceId,
      action: "upload_logs",
      note: "控制台手动拉取",
      operator: "console",
    })
      .then((res) => {
        this.setState({ pulling: null, confirmVisible: false, confirmTarget: null });
        if (res && res.duplicate) {
          message.info("该设备已有一条待执行的拉取命令（" + (res.commandId || "") + "），已延长有效期，不用重复下发");
        } else {
          message.success("拉取命令已下发，等设备下次轮询领取（通常 1 分钟内）");
        }
        this.load(true);
        this.loadCommands(row.deviceId);
      })
      .catch(() => this.setState({ pulling: null }));
  };

  doPing = (row) => {
    this.setState({ pulling: row.deviceId });
    dispatchDeviceCommandApi({
      appId: this.state.appId,
      deviceId: row.deviceId,
      action: "ping",
      note: "连通性测试",
      operator: "console",
    })
      .then(() => {
        message.success("已下发连通性测试");
        this.setState({ pulling: null });
        this.load(true);
      })
      .catch(() => this.setState({ pulling: null }));
  };

  loadCommands = (deviceId) => {
    this.setState({ commandDevice: deviceId, commandLoading: true, commands: [] });
    fetchDeviceCommandsApi({ appId: this.state.appId, deviceId, limit: 30 })
      .then((list) => this.setState({ commands: list || [], commandLoading: false }))
      .catch(() => this.setState({ commandLoading: false }));
  };

  closeCommands = () => this.setState({ commandDevice: null, commands: [] });

  cancelCommand = (cmd) => {
    cancelDeviceCommandApi({ appId: this.state.appId, id: cmd.id })
      .then(() => {
        message.success("命令已取消");
        if (this.state.commandDevice) this.loadCommands(this.state.commandDevice);
        this.load(true);
      })
      .catch(() => {});
  };

  // 「采集设备信息」：走同一条轮询命令通道，SDK 侧内置采集（宿主无需实现），回来说明它真的采到了才 ack。
  doCollect = (row) => {
    this.setState({ collecting: row.deviceId });
    dispatchDeviceCommandApi({
      appId: this.state.appId,
      deviceId: row.deviceId,
      action: "collect_info",
      note: "控制台手动采集设备信息",
      operator: "console",
    })
      .then(() => {
        message.success("采集命令已下发，等设备下次轮询执行（通常 1 分钟内）");
        this.setState({ collecting: null });
        this.load(true);
      })
      .catch(() => this.setState({ collecting: null }));
  };

  showInfo = (row) => {
    this.setState({ infoDevice: row.deviceId, infoLoading: true, infoData: null });
    fetchDeviceInfoApi({ appId: this.state.appId, deviceId: row.deviceId })
      .then((data) => this.setState({ infoData: data || null, infoLoading: false }))
      .catch(() => this.setState({ infoData: null, infoLoading: false }));
  };

  closeInfo = () => this.setState({ infoDevice: null, infoData: null });

  INFO_LABELS = {
    os: "系统", osVersion: "系统版本", machine: "机型标识", model: "型号",
    brand: "品牌", manufacture: "厂商", manufacturer: "厂商", deviceName: "设备名", abi: "ABI", sdkApiVersion: "API等级",
    appVersion: "应用版本", buildVersion: "构建号", bundleIdentifier: "包名", bundleName: "包名",
    cpuCount: "CPU核数", cpuUsagePercent: "CPU占用%", systemCpuUsagePercent: "系统CPU%", activeProcessorCount: "活动核数", processorCount: "核数",
    totalMemBytes: "总内存", freeMemBytes: "空闲内存", availMemBytes: "可用内存", wiredMemBytes: "系统占用内存", compressedMemBytes: "压缩内存",
    appPhysFootprintBytes: "应用占用内存", appPssKb: "应用PSS(KB)", memRssLimitBytes: "内存上限",
    dataTotalBytes: "存储总量", dataAvailBytes: "存储可用", dataUsedBytes: "存储已用", diskUsagePercent: "存储占用%",
    batteryLevelPercent: "电量%", batteryState: "充电状态", batteryTemperature: "电池温度(0.1℃)",
    thermalState: "温控等级", network: "网络", networkAvailable: "网络可用", screenScale: "屏幕倍率",
    screenWidthPt: "屏幕宽", screenHeightPt: "屏幕高", screenWidth: "屏幕宽", screenHeight: "屏幕高",
    timezone: "时区", locale: "语言", language: "语言", uptimeSeconds: "开机时长(秒)", collectedAt: "采集时间",
  };

  infoEntries = (info) => {
    if (!info || typeof info !== "object") return [];
    var self = this;
    var keys = Object.keys(info);
    var pri = keys.filter(function (k) { return self.INFO_LABELS[k]; });
    var rest = keys.filter(function (k) { return !self.INFO_LABELS[k]; });
    return pri.concat(rest).map(function (k) {
      var text = info[k];
      if (/Bytes$/.test(k) && typeof text === "number") {
        text = (text / 1024 / 1024).toFixed(1) + " MB";
      } else if (typeof text === "boolean") {
        text = text ? "是" : "否";
      } else if (typeof text === "object" && text !== null) {
        text = JSON.stringify(text);
      }
      return { key: k, label: self.INFO_LABELS[k] || k, text: String(text) };
    });
  };

  gotoCrashes = (row) => {
    window.location.hash = "#/crash-list?appId=" + encodeURIComponent(this.state.appId) +
      "&deviceId=" + encodeURIComponent(row.deviceId);
  };

  gotoLogs = (row) => {
    // 日志列表页要求 deviceId+platform+beginTime+endTime 四个参数齐全才会按设备过滤
    const end = Number(row.lastLogTime || row.lastCrashTime) || Date.now();
    const begin = end - 30 * 86400000;
    const qs = ["appId=" + encodeURIComponent(this.state.appId),
      "deviceId=" + encodeURIComponent(row.deviceId),
      "platform=" + encodeURIComponent(row.platform || 1),
      "beginTime=" + begin, "endTime=" + end].join("&");
    window.location.hash = "#/native-list?" + qs;
  };

  columns = () => [
    {
      title: "设备",
      dataIndex: "deviceId",
      render: (v, row) => (
        <div>
          <div style={{ fontFamily: "Menlo, monospace", fontSize: 12 }}>{v}</div>
          <div style={{ color: "#8c8c8c", fontSize: 12 }}>
            {[row.brand, row.model, row.sdkInt ? "API " + row.sdkInt : ""].filter(Boolean).join(" · ") || "机型未知"}
          </div>
        </div>
      ),
    },
    {
      title: "端",
      dataIndex: "platform",
      width: 96,
      render: (v) => {
        const m = PLATFORM_META[Number(v)];
        return m ? <Tag color={m.color}>{m.text}</Tag> : <Tag>未知</Tag>;
      },
    },
    {
      title: "版本",
      dataIndex: "appVersion",
      width: 130,
      render: (v, row) => <span>{v || "-"}{row.buildVersion ? " (" + row.buildVersion + ")" : ""}</span>,
    },
    {
      title: "在线",
      dataIndex: "online",
      width: 130,
      render: (v, row) =>
        v ? (
          <Tooltip title={"最近 " + relText(row.lastPollTime) + "轮询过，命令可即时送达"}>
            <Badge status="processing" text="在线" />
          </Tooltip>
        ) : (
          <Tooltip title={"上次轮询：" + fmtTime(row.lastPollTime) + "（客户端 SDK 需已接入命令轮询）"}>
            <Badge status="default" text={"离线 · " + relText(row.lastPollTime)} />
          </Tooltip>
        ),
    },
    {
      title: "最近上报",
      dataIndex: "lastLogTime",
      width: 120,
      render: (v, row) => (
        <a onClick={() => this.gotoLogs(row)}>{fmtTime(v || row.lastCrashTime)}</a>
      ),
    },
    {
      title: "日志",
      dataIndex: "logCount",
      width: 76,
      render: (v, row) => (Number(v) > 0 ? <a onClick={() => this.gotoLogs(row)}>{v}</a> : <span style={{ color: "#bfbfbf" }}>0</span>),
    },
    {
      title: "崩溃",
      dataIndex: "crashCount",
      width: 86,
      render: (v, row) =>
        Number(v) > 0 ? (
          <Tooltip title="查看该设备崩溃">
            <a onClick={() => this.gotoCrashes(row)}>
              <Badge status="error" text={Number(v) + " 次"} />
            </a>
          </Tooltip>
        ) : <span style={{ color: "#bfbfbf" }}>0</span>,
    },
    {
      title: "命令",
      dataIndex: "pendingCommands",
      width: 100,
      render: (v, row) =>
        Number(v) > 0 ? <Tag color="orange">待执行 {Number(v)}</Tag> : (
          <a onClick={() => this.loadCommands(row.deviceId)}>历史</a>
        ),
    },
    {
      title: "操作",
      key: "ops",
      width: 380,
      render: (v, row) => (
        <span>
          <Button
            type="primary"
            size="small"
            icon="download"
            loading={this.state.pulling === row.deviceId}
            onClick={() => this.askPull(row)}
          >
            拉取日志
          </Button>
          <Button size="small" style={{ marginLeft: 8 }} loading={this.state.collecting === row.deviceId} onClick={() => this.doCollect(row)}>采集设备信息</Button>
          <Button size="small" style={{ marginLeft: 8 }} onClick={() => this.showInfo(row)}>设备信息</Button>
          <Button size="small" style={{ marginLeft: 8 }} onClick={() => this.doPing(row)}>测试连通</Button>
          <Button size="small" style={{ marginLeft: 8 }} onClick={() => this.loadCommands(row.deviceId)}>命令记录</Button>
        </span>
      ),
    },
  ];

  render() {
    const { devices, total, loading, appId, commandDevice, commands, commandLoading } = this.state;
    return (
      <Card
        title={
          <span>
            设备清单
            <span style={{ color: "#8c8c8c", fontSize: 12, fontWeight: "normal", marginLeft: 12 }}>
              设备来自上报记录；「拉取日志」会下发命令，由设备下次轮询时上传当日日志
            </span>
          </span>
        }
        extra={
          <span>
            <Input.Search
              placeholder="按设备号/机型搜索"
              allowClear
              style={{ width: 220, marginRight: 8 }}
              onSearch={(v) => this.setState({ keyword: v }, () => this.load())}
            />
            <Button icon="reload" onClick={() => this.load()}>刷新</Button>
          </span>
        }
      >
        {!appId ? (
          <Empty description="请先在左下角选择应用" />
        ) : (
          <Table
            rowKey={(r) => r.appId + "|" + r.deviceId}
            dataSource={devices}
            columns={this.columns()}
            loading={loading}
            pagination={{ pageSize: 20, showTotal: () => "共 " + total + " 台" }}
            locale={{ emptyText: <Empty description="该应用还没有设备上报记录" /> }}
            size="middle"
          />
        )}
        <Modal
          title={"命令记录 · " + (commandDevice || "")}
          visible={!!commandDevice}
          onCancel={this.closeCommands}
          footer={<Button onClick={this.closeCommands}>关闭</Button>}
          width={860}
        >
          {commandLoading ? (
            <Spin />
          ) : (
            <Table
              rowKey={(r) => String(r.id)}
              dataSource={commands}
              pagination={false}
              size="small"
              locale={{ emptyText: <Empty description="还没下发过命令" /> }}
              columns={[
                { title: "#", dataIndex: "id", width: 56 },
                { title: "动作", dataIndex: "action", width: 100, render: (v) => ACTION_META[v] || v },
                {
                  title: "状态",
                  dataIndex: "status",
                  width: 110,
                  render: (v) => {
                    const m = COMMAND_STATUS_META[v] || { text: v, color: "default" };
                    return <Tooltip title={m.tip}><Tag color={m.color}>{m.text}</Tag></Tooltip>;
                  },
                },
                { title: "下发", dataIndex: "addTime", width: 120, render: fmtTime },
                { title: "领取", dataIndex: "deliveredTime", width: 120, render: fmtTime },
                { title: "回报", dataIndex: "ackTime", width: 120, render: fmtTime },
                { title: "回报内容", dataIndex: "resultNote", render: (v) => v || "-" },
                { title: "备注", dataIndex: "note", render: (v) => v || "-" },
                {
                  title: "操作",
                  key: "op",
                  width: 80,
                  render: (v, cmd) =>
                    cmd.status === "pending" ? (
                      <a onClick={() => this.cancelCommand(cmd)}>取消</a>
                    ) : null,
                },
              ]}
            />
          )}
        </Modal>
        <Modal
          title="确认拉取该设备日志？"
          visible={this.state.confirmVisible}
          onOk={this.doPull}
          onCancel={this.closeConfirm}
          okText="下发拉取命令"
          cancelText="取消"
          confirmLoading={!!this.state.pulling}
        >
          {this.state.confirmTarget ? (
            <div>
              <p>
                设备：
                <span style={{ fontFamily: "Menlo, monospace" }}>{this.state.confirmTarget.deviceId}</span>
              </p>
              <p>在线状态：{this.state.confirmTarget.online ? "在线，通常 1 分钟内送达" : "离线，命令将在有效期内等设备回来领取"}</p>
              <p style={{ color: "#8c8c8c" }}>
                命令有效期 30 分钟。设备领到后会立即落盘当前日志并上传当日日志包，完成后自动回报结果。
              </p>
            </div>
          ) : null}
        </Modal>
        <Drawer
          title={<span>设备信息 <span style={{ color: "#8c8c8c", fontSize: 12, fontWeight: "normal", marginLeft: 10, fontFamily: "Menlo, monospace" }}>{this.state.infoDevice}</span></span>}
          placement="right"
          width={720}
          visible={!!this.state.infoDevice}
          onClose={this.closeInfo}
        >
          {this.state.infoLoading ? (
            <Spin />
          ) : !this.state.infoData || !this.state.infoData.info ? (
            <Empty description={<span>该设备还没有上报过设备信息。<br />点列表里的「采集设备信息」下发命令，设备下次轮询（约 1 分钟内）会采集并回传。</span>} />
          ) : (
            <div>
              <p style={{ color: "#8c8c8c" }}>
                上报时间：{fmtTime(this.state.infoData.reportTime)}
                <Button size="small" style={{ marginLeft: 12 }} loading={this.state.collecting === this.state.infoDevice} onClick={() => this.doCollect({ deviceId: this.state.infoDevice })}>重新采集</Button>
                <Button size="small" style={{ marginLeft: 8 }} onClick={() => this.showInfo({ deviceId: this.state.infoDevice })}>刷新</Button>
              </p>
              <Descriptions size="small" column={2} bordered>
                {this.infoEntries(this.state.infoData.info).map((row) => (
                  <Descriptions.Item key={row.key} label={row.label}>{row.text}</Descriptions.Item>
                ))}
              </Descriptions>
            </div>
          )}
        </Drawer>
      </Card>
    );
  }
}

export default connect()(DeviceList);
