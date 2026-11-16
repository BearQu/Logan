import axios from "axios";
import { message } from 'antd';

const BASE_URL = process.defineEnv.API_BASE_URL;
const API_TIME_OUT = 30000;
let pendingRequests = [];

// axios instance
const instance = axios.create({
  baseURL: BASE_URL,
  timeout: API_TIME_OUT,
  withCredentials: true
});


// Native Apis

export function fetchNativeListInitData(appId) {
  return instance.get("/logan/latest.json", { params: { appId } });
}

export function fetchNativeTaskApi(deviceId, platform, beginTime, endTime, appId) {
  return instance.get("/logan/task/search.json", {
    params: {
      deviceId,
      platform,
      beginTime,
      endTime,
      appId
    }
  });
}

export function fetchNativeTaskInfoByTaskIdApi(taskId) {
  return instance.get(`/logan/task/${taskId}/info.json`);
}

export function fetchNativeLogTypesApi() {
  return instance.get("/logan/meta/logtypes.json");
}

export function fetchNativeTaskBriefsByTaskIdApi(taskId, logTypes, keyword) {
  return instance.get(`/logan/task/${taskId}/brief.json`, {
    params: {
      logTypes,
      keyword
    }
  });
}

export function fetchNativeTaskDetailsByDetailIdsApi(detailIds) {
  return instance.get("/logan/task/query/details.json", {
    params: {
      detailIds: detailIds
    }
  });
}

export function fetchNativeSingleLogDetailByLogIdApi(logId) {
  return instance.get(`/logan/task/${logId}/detail.json`);
}

export function fetchNativeDownloadUrlApi(taskId) {
  return instance.get(`/logan/task/${taskId}/download.json`);
}


// Crash apis (崩溃维度)

export function fetchCrashIssuesApi(appId, limit) {
  return instance.get("/logan/crash/issues/latest.json", { params: { appId, limit } });
}

export function fetchCrashStatsApi(appId) {
  return instance.get("/logan/crash/stats.json", { params: { appId } });
}

export function fetchCrashTrendApi(appId, beginTime, endTime) {
  return instance.get("/logan/crash/trend.json", { params: { appId, beginTime, endTime } });
}

export function fetchCrashInstancesApi(appId, fingerprint, limit) {
  return instance.get("/logan/crash/instances.json", { params: { appId, fingerprint, limit } });
}

export function fetchCrashIssueDetailApi(id) {
  return instance.get("/logan/crash/issues/detail.json", { params: { id } });
}

export function updateCrashIssueStatusApi(id, status) {
  return instance.get("/logan/crash/issues/status.json", { params: { id, status } });
}

export function fetchSingleCrashApi(crashId) {
  return instance.get(`/logan/crash/${crashId}/info.json`);
}

export function fetchCrashSearchApi(params) {
  return instance.get("/logan/crash/search.json", { params });
}

export function fetchCrashIssueSearchApi(params) {
  return instance.get("/logan/crash/issues/search.json", { params });
}

export function fetchCrashFilterValuesApi(appId) {
  return instance.get("/logan/crash/filters/values.json", { params: { appId } });
}


// Web apis


export function fetchWebDownloadUrlApi(tasks) {
  return instance.get("/logan/web/getDownLoadUrl.json", {
    params: {
      tasks: tasks
    }
  })
}

export function fetchWebListInitData(appId) {
  return instance.get("/logan/web/latest.json", { params: { appId } });
}

export function fetchWebTaskApi(deviceId, beginTime, endTime, appId) {
  return instance.get("/logan/web/search.json", {
    params: {
      deviceId,
      beginTime,
      endTime,
      appId
    }
  })
}

export function fetchWebBriefsApi(taskId, logTypes, keyword) {
  return instance.get("/logan/web/detailIndex.json", {
    params: {
      tasks: taskId,
      logTypes: logTypes,
      keyword: keyword,
      beginTime: null,
      endTime: null
    }
  })
}

export function fetchWebTaskDetailsByDetailIdsApi(detailIds) {
  return instance.get("/logan/web/details.json", {
    params: {
      detailIds
    }
  })
}

export function fetchWebTaskInfoByTaskIdApi(taskId) {
  return instance.get("/logan/web/taskDetail.json", {
    params: {
      tasks: taskId
    }
  })
}

export function fetchWebSingleLogDetailByLogIdApi(detailId) {
  return instance.get("/logan/web/logDetail.json", {
    params: {
      detailId
    }
  })
}


// private request configs

const RemovePendingRequest = (config) => {
  pendingRequests.forEach((request, idx) => {
    if (request.id === config.url + '&' + config.method) {
      request.cancel();
      pendingRequests.splice(idx, 1);
    }
  });
};

const AddPendingRequest = (config) => {
  return new axios.CancelToken(c => {
    pendingRequests.push({
      id: config.url + '&' + config.method,
      cancel: c
    });
  });
};

instance.interceptors.request.use((config) => {
  if (config.headers.cancelOthers) {
    delete config.headers.cancelOthers;
    RemovePendingRequest(config);
    config.cancelToken = AddPendingRequest(config);
  }
  if (config.headers.removeXRequestedWith) {
    delete config.headers.removeXRequestedWith;
    delete config.headers['X-Requested-With'];
  }
  return config;
});

function onRequestFulfilled(response) {
  RemovePendingRequest(response.config);
  let {code, msg, data} = response.data;
  if (Number(code) !== 200) {
    message.warning(`请求出错：${msg}`);
    return Promise.reject(response.data);
  }
  return data;
}

function onRequestRejected(error) {
  if (!error.request) {
    return null;
  }
  let status = error.response && error.response.status;
  message.error(`请求出错${status || error.message}`);
  return Promise.reject(error);
}

instance.interceptors.response.use(onRequestFulfilled, onRequestRejected);




export function fetchAppListApi() {
  return instance.get('/logan/app/list.json');
}

export function createAppApi(body) {
  return instance.post("/logan/app/create.json", body);
}

export function saveAppApi(body) {
  return instance.post("/logan/app/save.json", body);
}

export function fetchAppInfoApi(appId) {
  return instance.get('/logan/app/info.json', { params: { appId } });
}

export function fetchCrashEvidenceApi(params) {
  return instance.get('/logan/crash/evidence.json', { params });
}

export function fetchCrashTimelineApi(params) {
  return instance.get('/logan/crash/timeline.json', { params });
}

export function fetchCrashDaysApi(params) {
  return instance.get('/logan/crash/days.json', { params });
}

export function fetchCrashFixesApi(params) {
  return instance.get('/logan/crash/fixes.json', { params });
}

export function reportCrashFixApi(body) {
  return instance.post('/logan/crash/fix/report.json', body);
}

// ---- 设备清单与「主动拉日志」命令通道 ----
export function fetchDeviceListApi(params) {
  return instance.get("/logan/device/list.json", { params });
}

export function dispatchDeviceCommandApi(body) {
  return instance.post("/logan/device/command/dispatch.json", body);
}

export function cancelDeviceCommandApi(body) {
  return instance.post("/logan/device/command/cancel.json", body);
}

export function fetchDeviceCommandsApi(params) {
  return instance.get("/logan/device/command/list.json", { params });
}


export function fetchDeviceInfoApi(params) {
  return instance.get("/logan/device/info.json", { params });
}

// ---- 符号表治理（上传 mapping/dSYM/so/sourcemap + 平台侧真反混淆）----
export function uploadSymbolApi(body) {
  return instance.post("/logan/symbol/upload.json", body);
}

export function fetchSymbolListApi(params) {
  return instance.get("/logan/symbol/list.json", { params });
}

export function deleteSymbolApi(body) {
  return instance.post("/logan/symbol/delete.json", body);
}

export function applySymbolApi(body) {
  return instance.post("/logan/symbol/apply.json", body);
}

// ---- 应用创建：appId 由平台生成（包名+端后缀，撞名自动让位），用户不手填 ----
export function prepareAppApi(body) {
  return instance.post("/logan/app/prepare.json", body);
}
