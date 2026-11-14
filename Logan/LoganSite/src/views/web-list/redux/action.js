import {
  WEB_UPDATE_FILTER_CONDITIONS,
  WEB_UPDATE_TASKS,
  WEB_CHANGE_LOADING
} from "./reducer";
import {fetchWebTaskApi, fetchWebListInitData} from "../../../common/api";
import { getCurrentAppId } from "../../../common/currentApp";


export function fetchInitData() {
  return (dispatch) => {
    return fetchWebListInitData(getCurrentAppId())
      .then(data => {
        dispatch({
          type: WEB_UPDATE_TASKS,
          tasks: data
        })
      })
  }
}

export function updateFilterConditions(newFilterConditions) {
  return (dispatch) => {
    dispatch({
      type: WEB_UPDATE_FILTER_CONDITIONS,
      filterConditions: newFilterConditions
    });
  };
}

export function changeLoading(loading) {
  return (dispatch, getState) => {
    dispatch({
      type: WEB_CHANGE_LOADING,
      loading: loading
    });
  };
}

export function fetchTasks({deviceId, beginTime, endTime}) {
  return (dispatch, getState) => {
    return fetchWebTaskApi(deviceId, beginTime, endTime, getCurrentAppId())
      .then(data => {
        dispatch({
          type: WEB_UPDATE_TASKS,
          tasks: data
        });
      });
  };
}
