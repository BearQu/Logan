import React, { Component } from "react";
import {Switch, Route, Redirect} from "react-router-dom";
import { ConnectedRouter } from 'connected-react-router'
import {Layout} from "antd";
import {history} from "./store";
import Overview from "./views/overview";
import AlertView from "./views/alert";
import NativeList from "./views/native-list";
import NativeLogDetail from "./views/native-log-detail";
import WebList from "./views/web-list";
import CrashList from "./views/crash-list";
import CrashDetail from "./views/crash-detail";
import WebLogDetail from "./views/web-detail";
import AppList from "./views/app-list";
import HttpList from "./views/http-list";
import ErrorList from "./views/error-list";
import DeviceList from "./views/device-list";
import ConfigList from "./views/config-list";
import Sider from "./common/components/Sider/Sider";
import "antd/dist/antd.css";
import "./app.scss";

class App extends Component {

  componentDidCatch(error, errorInfo) {
    console.log(error);
  }

  render() {
    return (
      <ConnectedRouter history={history}>
        <>
          <Layout style={{height: "100%"}}>
            <Sider/>
            <div className="app">
              <Switch>
                <Redirect exact from="/" to="/overview"/>
                <Route exact path="/overview" component={Overview}/>
                <Route exact path="/alert" component={AlertView}/>
                <Route exact path="/native-list" component={NativeList}/>
                <Route exact path="/native-log-detail" component={NativeLogDetail}/>
                <Route exact path="/web-list" component={WebList}/>
                <Route exact path="/web-detail" component={WebLogDetail}/>
                <Route exact path="/crash-list" component={CrashList}/>
                <Route exact path="/crash-detail" component={CrashDetail}/>
                <Route exact path="/app-list" component={AppList}/>
        <Route exact path="/device-list" component={DeviceList}/>
                <Route exact path="/http-list" component={HttpList}/>
                <Route exact path="/error-list" component={ErrorList}/>
                <Route exact path="/config-list" component={ConfigList}/>
              </Switch>
            </div>
          </Layout>
        </>
      </ConnectedRouter>
    );
  }
}

export default App;
