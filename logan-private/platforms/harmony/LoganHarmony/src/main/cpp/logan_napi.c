/*
 * LoganHarmony napi 绑定：把共享 clogan 内核暴露给 ArkTS。
 * 与 Android liblogan.so(JNI) 同一份 Clogan/mbedtls 源码 —— 落盘格式一致，Server 零改动。
 */
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <dirent.h>
#include <sys/stat.h>
#include "napi/native_api.h"
#include "clogan_core.h"

static napi_value LoganInit(napi_env env, napi_callback_info info) {
    size_t argc = 5;
    napi_value args[5];
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);
    char cache[512] = {0}, path[512] = {0}, key[32] = {0}, iv[32] = {0};
    size_t n;
    napi_get_value_string_utf8(env, args[0], cache, sizeof(cache), &n);
    napi_get_value_string_utf8(env, args[1], path, sizeof(path), &n);
    int max_file = 5 * 1024 * 1024;
    napi_get_value_int32(env, args[2], &max_file);
    napi_get_value_string_utf8(env, args[3], key, sizeof(key), &n);
    napi_get_value_string_utf8(env, args[4], iv, sizeof(iv), &n);
    int r = clogan_init(cache, path, max_file, key, iv);
    napi_value out;
    napi_create_int32(env, r, &out);
    return out;
}

static napi_value LoganOpen(napi_env env, napi_callback_info info) {
    size_t argc = 1; napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);
    char name[512] = {0}; size_t n;
    napi_get_value_string_utf8(env, args[0], name, sizeof(name), &n);
    napi_value out; napi_create_int32(env, clogan_open(name), &out);
    return out;
}

static napi_value LoganWrite(napi_env env, napi_callback_info info) {
    size_t argc = 3; napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);
    int flag = 2; long long ts = 0;
    napi_get_value_int32(env, args[0], &flag);
    napi_get_value_int64(env, args[1], (int64_t *)&ts);
    char buf[16 * 1024] = {0}; size_t n = 0;
    napi_get_value_string_utf8(env, args[2], buf, sizeof(buf), &n);
    napi_value out;
    napi_create_int32(env, clogan_write(flag, buf, ts, (char *)"logan", 0, 1), &out);
    return out;
}

static napi_value LoganFlush(napi_env env, napi_callback_info info) {
    napi_value out; napi_create_int32(env, clogan_flush(), &out);
    return out;
}

static napi_value LoganDebug(napi_env env, napi_callback_info info) {
    size_t argc = 1; napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);
    int on = 0; napi_get_value_int32(env, args[0], &on);
    clogan_debug(on);
    return NULL;
}

/* 列目录下 *.logan 文件（崩溃补传 B 通道用），返回 JSON 数组字符串 */
static napi_value ListLogFiles(napi_env env, napi_callback_info info) {
    size_t argc = 1; napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);
    char dir[512] = {0}; size_t n;
    napi_get_value_string_utf8(env, args[0], dir, sizeof(dir), &n);
    char json[8192] = "["; size_t off = 1; int first = 1;
    DIR *d = opendir(dir);
    if (d) {
        struct dirent *e;
        while ((e = readdir(d)) != NULL) {
            size_t l = strlen(e->d_name);
            if (l > 6 && strcmp(e->d_name + l - 6, ".logan") == 0) {
                struct stat st; char full[1024];
                snprintf(full, sizeof(full), "%s/%s", dir, e->d_name);
                if (stat(full, &st) == 0) {
                    off += snprintf(json + off, sizeof(json) - off, "%s{\"name\":\"%s\",\"size\":%lld}",
                                    first ? "" : ",", e->d_name, (long long)st.st_size);
                    first = 0;
                    if (off > sizeof(json) - 256) break;
                }
            }
        }
        closedir(d);
    }
    strcat(json, "]");
    napi_value out; napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &out);
    return out;
}

/* 通用目录名列表（faultlogger 补读用），JSON 数组 */
static napi_value ListDir(napi_env env, napi_callback_info info) {
    size_t argc = 1; napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);
    char dir[512] = {0}; size_t n;
    napi_get_value_string_utf8(env, args[0], dir, sizeof(dir), &n);
    char json[8192] = "["; size_t off = 1; int first = 1;
    DIR *d = opendir(dir);
    if (d) {
        struct dirent *e;
        while ((e = readdir(d)) != NULL) {
            if (e->d_name[0] == '.') continue;
            off += snprintf(json + off, sizeof(json) - off, "%s\"%s\"", first ? "" : ",", e->d_name);
            first = 0;
            if (off > sizeof(json) - 256) break;
        }
        closedir(d);
    }
    strcat(json, "]");
    napi_value out; napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &out);
    return out;
}

/* 写小 JSON 文件（seen 名单） */
static napi_value WriteFileText(napi_env env, napi_callback_info info) {
    size_t argc = 2; napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);
    char p[1024] = {0}; size_t n;
    napi_get_value_string_utf8(env, args[0], p, sizeof(p), &n);
    size_t len = 0;
    napi_get_value_string_utf8(env, args[1], NULL, 0, &len);
    char *buf = (char *)calloc(len + 1, 1);
    napi_get_value_string_utf8(env, args[1], buf, len + 1, &n);
    FILE *f = fopen(p, "wb");
    if (f) { fwrite(buf, 1, len, f); fclose(f); }
    free(buf);
    napi_value out; napi_create_int32(env, f ? 1 : 0, &out);
    return out;
}

/* 删除补传成功的文件 */
static napi_value DeleteFile(napi_env env, napi_callback_info info) {
    size_t argc = 1; napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);
    char p[1024] = {0}; size_t n;
    napi_get_value_string_utf8(env, args[0], p, sizeof(p), &n);
    napi_value out; napi_create_int32(env, remove(p) == 0 ? 1 : 0, &out);
    return out;
}

/* 读文件全量字节（崩溃 envelope 文件 B 通道用），返回字符串 */
static napi_value ReadFileText(napi_env env, napi_callback_info info) {
    size_t argc = 1; napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);
    char p[1024] = {0}; size_t n;
    napi_get_value_string_utf8(env, args[0], p, sizeof(p), &n);
    FILE *f = fopen(p, "rb");
    char empty[1] = {0};
    if (!f) { napi_value e; napi_create_string_utf8(env, empty, 0, &e); return e; }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    char *buf = (char *)calloc(sz + 1, 1);
    if (fread(buf, 1, sz, f) != (size_t)sz) { /* 短读容忍 */ }
    fclose(f);
    napi_value out; napi_create_string_utf8(env, buf, (size_t)sz, &out);
    free(buf);
    return out;
}

napi_value napi_register_module_v1(napi_env env, napi_value exports) {
    napi_value fn;
    napi_create_function(env, "loganInit", NAPI_AUTO_LENGTH, LoganInit, NULL, &fn);
    napi_set_named_property(env, exports, "loganInit", fn);
    napi_create_function(env, "loganOpen", NAPI_AUTO_LENGTH, LoganOpen, NULL, &fn);
    napi_set_named_property(env, exports, "loganOpen", fn);
    napi_create_function(env, "loganWrite", NAPI_AUTO_LENGTH, LoganWrite, NULL, &fn);
    napi_set_named_property(env, exports, "loganWrite", fn);
    napi_create_function(env, "loganFlush", NAPI_AUTO_LENGTH, LoganFlush, NULL, &fn);
    napi_set_named_property(env, exports, "loganFlush", fn);
    napi_create_function(env, "loganDebug", NAPI_AUTO_LENGTH, LoganDebug, NULL, &fn);
    napi_set_named_property(env, exports, "loganDebug", fn);
    napi_create_function(env, "listLogFiles", NAPI_AUTO_LENGTH, ListLogFiles, NULL, &fn);
    napi_set_named_property(env, exports, "listLogFiles", fn);
    napi_create_function(env, "listDir", NAPI_AUTO_LENGTH, ListDir, NULL, &fn);
    napi_set_named_property(env, exports, "listDir", fn);
    napi_create_function(env, "writeFileText", NAPI_AUTO_LENGTH, WriteFileText, NULL, &fn);
    napi_set_named_property(env, exports, "writeFileText", fn);
    napi_create_function(env, "deleteFile", NAPI_AUTO_LENGTH, DeleteFile, NULL, &fn);
    napi_set_named_property(env, exports, "deleteFile", fn);
    napi_create_function(env, "readFileText", NAPI_AUTO_LENGTH, ReadFileText, NULL, &fn);
    napi_set_named_property(env, exports, "readFileText", fn);
    return exports;
}
