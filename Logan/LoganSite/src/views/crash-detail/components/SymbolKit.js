import React, { Component } from "react";
import {
  Card, Button, Tag, Upload, Icon, message, Modal, Table, Empty, Spin, Tooltip, Select, Popconfirm,
} from "antd";
import { uploadSymbolApi, fetchSymbolListApi, deleteSymbolApi, applySymbolApi } from "../../../common/api";

const KIND_LABEL = {
  mapping: "Android mapping.txt",
  dsym: "iOS dSYM 归档",
  so: "native .so 归档",
  sourcemap: "H5 sourcemap",
};
const TEXTUAL_KINDS = { java: true, anr: true, app: true };

/**
 * 符号表卡：归档 mapping / dSYM / so / sourcemap，并对 Android java/anr/app 崩溃在平台侧做真反混淆
 * （后端解析 mapping.txt 逐帧还原，并统计还原了几帧）。native 崩溃平台不伪造符号化结果，
 * 只归档 + 供本地 atos / addr2line 下载。
 */
export default class SymbolKit extends Component {

  state = { list: [], loading: false, applying: false, uploading: false, result: null, pickKind: undefined, crashId: null };

  componentDidMount() {
    this.loadList();
  }

  componentDidUpdate(prev) {
    if (prev.reloadNonce !== this.props.reloadNonce) this.loadList();
    if (prev.instances !== this.props.instances && !this.state.crashId && this.props.instances && this.props.instances.length) {
      this.setState({ crashId: this.props.instances[0].id });
    }
  }

  appId = () => (this.props.issue && this.props.issue.appId) || "";

  loadList = () => {
    const appId = this.appId();
    if (!appId) return;
    this.setState({ loading: true });
    fetchSymbolListApi({ appId: appId, limit: 50 })
      .then((data) => this.setState({ list: data || [], loading: false }))
      .catch(() => this.setState({ list: [], loading: false }));
  };

  readFile = (file) =>
    new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const dataUrl = String(reader.result || "");
        const idx = dataUrl.indexOf(",");
        resolve(idx >= 0 ? dataUrl.slice(idx + 1) : dataUrl);
      };
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });

  guessKind = (name) => {
    const lower = String(name || "").toLowerCase();
    if (lower.indexOf("dsym") >= 0 || /\.(zip|tar|gz)$/.test(lower)) return "dsym";
    if (/\.so(\.|$)/.test(lower) || lower.indexOf("lib") === 0) return "so";
    if (/\.map$/.test(lower)) return "sourcemap";
    return "mapping";
  };

  beforeUpload = async (file) => {
    const appId = this.appId();
    if (!appId) { message.error("缺少应用标识"); return false; }
    const kind = this.state.pickKind || this.guessKind(file.name);
    this.setState({ uploading: true });
    try {
      const contentBase64 = await this.readFile(file);
      const issue = this.props.issue || {};
      const res = await uploadSymbolApi({
        appId: appId,
        platform: Number(issue.platform) || 0,
        appVersion: issue.appVersion || null,
        kind: kind,
        fileName: file.name,
        contentBase64: contentBase64,
        uploader: "console",
        source: "console",
      });
      if (res && res.duplicate) message.warning("相同内容的符号表已归档，已复用既有记录");
      else message.success("符号表已归档");
      this.loadList();
    } catch (e) {
      // 拦截器已提示
    } finally {
      this.setState({ uploading: false });
    }
    return false;
  };

  apply = async () => {
    const crashId = this.state.crashId;
    if (!crashId) { message.error("还没有可套用的崩溃实例"); return; }
    this.setState({ applying: true });
    try {
      const r = await applySymbolApi({ crashId: crashId });
      this.setState({ result: r || null });
      if (r && r.hasMapping) {
        if (Number(r.changedFrames) > 0) message.success("反混淆完成：还原 " + r.changedFrames + " / " + r.totalFrames + " 帧");
        else message.warning("已套用 mapping，但没有帧被还原（堆栈可能未混淆，或 mapping 与版本不匹配）");
      }
    } catch (e) {
      // 拦截器已提示
    } finally {
      this.setState({ applying: false });
    }
  };

  remove = async (row) => {
    try {
      await deleteSymbolApi({ id: row.id });
      message.success("已删除");
      this.loadList();
    } catch (e) {
      // 拦截器已提示
    }
  };

  fmtSize = (v) => {
    const n = Number(v) || 0;
    if (n > 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + " MB";
    return (n / 1024).toFixed(1) + " KB";
  };

  render() {
    const { list, loading, applying, uploading, result } = this.state;
    const issue = this.props.issue || {};
    const textual = !!TEXTUAL_KINDS[String(issue.kind || "").toLowerCase()];
    const base = window.API_BASE_URL || "/logan-web";
    const instances = this.props.instances || [];
    return (
      <Card
        title={<span><Icon type="skin" style={{ color: "#13c2c2", marginRight: 8 }} />符号表</span>}
        bodyStyle={{ padding: "12px 18px" }}
        style={{ marginTop: 16 }}
        extra={
          <Tooltip title="上传后按 appId + 版本归档；Android 崩溃可在平台侧直接反混淆">
            <Icon type="question-circle-o" style={{ color: "#bfbfbf" }} />
          </Tooltip>
        }
      >
        <div style={{ marginBottom: 10 }}>
          <Select
            size="small"
            style={{ width: 170, marginRight: 8 }}
            placeholder="自动判断类型"
            allowClear
            value={this.state.pickKind}
            onChange={(v) => this.setState({ pickKind: v })}
          >
            {Object.keys(KIND_LABEL).map((k) => (
              <Select.Option key={k} value={k}>{KIND_LABEL[k]}</Select.Option>
            ))}
          </Select>
          <Upload beforeUpload={this.beforeUpload} showUploadList={false}>
            <Button size="small" icon="upload" loading={uploading}>上传符号表</Button>
          </Upload>
          <Button
            size="small"
            type="primary"
            ghost
            icon="thunderbolt"
            loading={applying}
            style={{ marginLeft: 8 }}
            onClick={this.apply}
            disabled={!textual}
          >
            套用符号表
          </Button>
          <Button size="small" icon="reload" style={{ marginLeft: 8 }} onClick={this.loadList}>刷新</Button>
        </div>
        {instances.length > 1 ? (
          <div style={{ marginBottom: 10 }}>
            <span style={{ color: "#8c8c8c", fontSize: 12, marginRight: 8 }}>对哪个实例套用：</span>
            <Select
              size="small"
              style={{ width: 320 }}
              value={this.state.crashId}
              onChange={(v) => this.setState({ crashId: v })}
            >
              {instances.map((r) => (
                <Select.Option key={String(r.id)} value={r.id}>
                  #{String(r.id)} {String(r.brand || "")} {String(r.model || "")} · {String(r.appVersion || "")}
                </Select.Option>
              ))}
            </Select>
          </div>
        ) : null}
        {!textual ? (
          <div style={{ color: "#8c8c8c", fontSize: 12, marginBottom: 10 }}>
            当前崩溃类型为 native/系统级：平台侧不伪造符号化结果。请归档对应 dSYM / .so，在本地用 atos 或
            ndk addr2line 符号化（下方列表可直接下载归档）。
          </div>
        ) : null}
        {loading ? <Spin /> : (
          <Table
            rowKey={(r) => String(r.id)}
            size="small"
            dataSource={list}
            pagination={false}
            locale={{ emptyText: <Empty description="还没有上传符号表" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
            columns={[
              { title: "类型", dataIndex: "kind", width: 110, render: (v) => <Tag>{KIND_LABEL[v] || v}</Tag> },
              { title: "文件", dataIndex: "fileName", render: (v) => <span style={{ fontFamily: "Menlo, monospace", fontSize: 12 }}>{v}</span> },
              { title: "版本", dataIndex: "appVersion", width: 90, render: (v) => v || "-" },
              { title: "大小", dataIndex: "fileSize", width: 80, render: this.fmtSize },
              { title: "来源", dataIndex: "source", width: 80, render: (v) => v || "console" },
              {
                title: "操作",
                key: "op",
                width: 110,
                render: (v, row) => (
                  <span>
                    <a href={base + "/logan/symbol/download.json?id=" + row.id} target="_blank" rel="noreferrer">下载</a>
                    <Popconfirm title="删除该符号表归档？" onConfirm={() => this.remove(row)} okText="删除" cancelText="取消">
                      <a style={{ marginLeft: 8, color: "#f5222d" }}>删除</a>
                    </Popconfirm>
                  </span>
                ),
              },
            ]}
          />
        )}
        <Modal
          title="符号表套用结果"
          visible={!!result}
          onCancel={() => this.setState({ result: null })}
          footer={<Button onClick={() => this.setState({ result: null })}>关闭</Button>}
          width={900}
        >
          {result ? (
            <div>
              {result.hasMapping ? (
                <div>
                  <p>
                    <Tag color="green">已套用 mapping</Tag>
                    还原 <b style={{ color: "#52c41a" }}>{result.changedFrames}</b> / {result.totalFrames} 帧
                    {result.versionFallback ? <Tag color="orange" style={{ marginLeft: 8 }}>版本未命中，已回落最新 mapping</Tag> : null}
                  </p>
                  <p style={{ color: "#8c8c8c", fontSize: 12 }}>
                    使用文件：{result.mapping ? result.mapping.fileName : "-"}（{this.fmtSize(result.mapping ? result.mapping.fileSize : 0)}，
                    解析出 {result.mapping ? result.mapping.classCount : 0} 个类映射）
                  </p>
                  <pre style={{ background: "#1e1e1e", color: "#d4d4d4", padding: 16, borderRadius: 6, maxHeight: 420, overflow: "auto", fontSize: 12, lineHeight: 1.7, whiteSpace: "pre-wrap" }}>
                    {result.originalContent}
                  </pre>
                </div>
              ) : (
                <Empty description={result.note || result.error || "没有可用的符号表"} />
              )}
            </div>
          ) : null}
        </Modal>
      </Card>
    );
  }
};
