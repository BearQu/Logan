import "antd/dist/antd.css";
import React, { Component } from "react";
import { connect } from "react-redux";
import { Layout, Menu, Icon } from "antd";
import { isEqual } from "lodash";
import image from "./new_logan_logo_white.png";
import AppSwitcher from "../AppSwitcher/AppSwitcher";
import "./style.scss";

class Sider extends Component {

  state = {
    selectedKeys: ["0"]
  };

  selectedKeysFromPathname() {
    const { pathname } = this.props;
    if (pathname === "/native-list" || pathname === "/native-log-detail") return ["0"];
    if (pathname === "/web-list" || pathname === "/web-detail") return ["1"];
    if (pathname === "/crash-list" || pathname === "/crash-detail") return ["2"];
    if (pathname === "/app-list") return ["3"];
    if (pathname === "/device-list") return ["4"];
    return ["0"];
  }

  render() {
    return (
      <Layout.Sider style={style.sider} trigger={null}>
        <div>
          <div className="sider-logo" style={style.logo} />
          <Menu theme="dark" mode="inline" defaultSelectedKeys={["0"]} selectedKeys={this.selectedKeysFromPathname()} onClick={this.handleMenuClick}>
            <Menu.Item key="0">
              <Icon type="mobile" />
              <span>Native日志</span>
            </Menu.Item>
            <Menu.Item key="1">
              <Icon type="html5" />
              <span>Web日志</span>
            </Menu.Item>
            <Menu.Item key="2">
              <Icon type="bug" />
              <span>崩溃分析</span>
            </Menu.Item>
            <Menu.Item key="3">
              <Icon type="appstore" />
              <span>应用清单</span>
            </Menu.Item>
            <Menu.Item key="4">
              <Icon type="desktop" />
              <span>设备清单</span>
            </Menu.Item>
          </Menu>
        </div>
        <AppSwitcher />
      </Layout.Sider>
    );
  }

  componentDidMount() {
    const {pathname} = this.props;
    if (pathname === "/native-list" || pathname === "/native-log-detail") {
      this.setState({
        selectedKeys: ["0"]
      });
    } else if (pathname === "/web-list" || pathname === "/web-detail") {
      this.setState({
        selectedKeys: ["1"]
      });
    } else if (pathname === "/crash-list" || pathname === "/crash-detail") {
      this.setState({
        selectedKeys: ["2"]
      });
    } else {
      this.setState({
        selectedKeys: ["0"]
      });
    }
  }

  shouldComponentUpdate(nextProps, nextState, nextContext) {
    return !isEqual(nextState, this.state) || !isEqual(nextProps, this.props);
  }


  componentDidUpdate() {
    this.selectedKeysFromPathname();
  }

  handleMenuClick = ({ key }) => {
    if (key === "0") {
      window.location.hash = "#/native-list";
      this.setState({
        selectedKeys: ["0"]
      });
    } else if (key === "1") {
      window.location.hash ="#/web-list";
      this.setState({
        selectedKeys: ["1"]
      });
    } else if (key === "2") {
      window.location.hash = "#/crash-list";
      this.setState({
        selectedKeys: ["2"]
      });
    } else if (key === "3") {
      window.location.hash = "#/app-list";
      this.setState({
        selectedKeys: ["3"]
      });
    } else if (key === "4") {
      window.location.hash = "#/device-list";
      this.setState({
        selectedKeys: ["4"]
      });
    } else {
      window.location.hash = "#/native-list";
      this.setState({
        selectedKeys: ["0"]
      });
    }
  }
}

const style = {
  sider: {
    height: "100%"
  },
  logo: {
    backgroundImage: image,
    backgroundRepeat: "no-repeat",
    backgroundSize: "cover",
    color: "#ffffff",
    textAlign: "center",
    lineHeight: "32px",
    height: "80px",
    margin: "5px",
    cursor: "pointer"
  }
};


function mapStateToProps(state) {
  return {
    pathname: state.router.location.pathname,
    search: state.router.location.search,
    hash: state.router.location.hash,
  }
}

export default connect(mapStateToProps)(Sider);
