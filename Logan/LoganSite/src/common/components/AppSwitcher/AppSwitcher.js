import React, { PureComponent } from 'react';
import { Select, Avatar, Icon, Tag } from 'antd';
import { getCurrentAppId, setCurrentAppId, ensureApp, getCachedApps, subscribe } from '../../currentApp';
import { SOURCE_META } from '../../../consts/crash';

const Option = Select.Option;

/**
 * 全局应用切换器：放在侧边栏底部，切换后所有页面按新 appId 重载。
 * 图标由客户端上报（/logan/app/save.json iconBase64），没上报的显示首字母占位。
 */
export default class AppSwitcher extends PureComponent {
  state = { apps: getCachedApps(), appId: getCurrentAppId() };

  componentDidMount() {
    this._unsub = subscribe((appId) => this.setState({ appId }));
    ensureApp().then((apps) => this.setState({ apps: apps || [] }));
  }

  componentWillUnmount() {
    if (this._unsub) {
      this._unsub();
    }
  }

  onChange = (appId) => {
    setCurrentAppId(appId);
    this.setState({ appId });
  };

  render() {
    const { apps, appId } = this.state;
    const cur = apps.find((a) => a.appId === appId);
    const src = cur && cur.source ? SOURCE_META[cur.source] : null;
    return (
      <div className="logan-app-switcher" style={{ padding: '12px 16px', borderTop: '1px solid rgba(255,255,255,.12)' }}>
        <div style={{ color: 'rgba(255,255,255,.55)', fontSize: 12, marginBottom: 6 }}>
          <Icon type="appstore" style={{ marginRight: 6 }} />当前应用
        </div>
        <Select
          showSearch
          style={{ width: '100%' }}
          value={appId || undefined}
          placeholder={apps.length ? '选择应用' : '尚无应用，等客户端上报后自动登记'}
          optionFilterProp="children"
          onChange={this.onChange}
          dropdownMatchSelectWidth={false}
        >
          {apps.map((a) => (
            <Option key={a.appId} value={a.appId}>
              <span role="img" style={{ marginRight: 6 }}>
                {a.iconPath ? '🖼️' : '📦'}
              </span>
              {a.appName || a.appId}
              <span style={{ color: '#999', marginLeft: 6, fontSize: 12 }}>{a.appId}</span>
            </Option>
          ))}
        </Select>
        {cur ? (
          <div style={{ marginTop: 8, lineHeight: '20px' }}>
            {cur.iconPath ? (
              <Avatar shape="square" size={22} src={'/logan-web/logan/app/icon.json?appId=' + encodeURIComponent(cur.appId)} style={{ marginRight: 6, verticalAlign: 'middle' }} />
            ) : (
              <Avatar shape="square" size={22} style={{ marginRight: 6, verticalAlign: 'middle', background: '#2f54eb', fontSize: 12 }}>
                {(cur.appName || cur.appId || '?').slice(0, 1).toUpperCase()}
              </Avatar>
            )}
            <span style={{ color: 'rgba(255,255,255,.75)', fontSize: 12 }}>
              {cur.latestAppVersion ? 'v' + cur.latestAppVersion : '版本未知'}
            </span>
            {src ? <Tag color={src.color} style={{ marginLeft: 6, fontSize: 11 }}>{src.text}</Tag> : null}
          </div>
        ) : null}
      </div>
    );
  }
}
