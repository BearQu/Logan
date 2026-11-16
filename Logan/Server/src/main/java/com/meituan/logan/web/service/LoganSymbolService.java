package com.meituan.logan.web.service;

import com.meituan.logan.web.dto.LoganSymbolDTO;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 符号表治理（用户要求：崩溃解析应有上传符号表的位置）。
 * mapping/sourcemap 文本支持平台侧 Android 反混淆应用；dsym/so 仅归档+下载（真正 Mach-O 符号化
 * 需 atos/dwarfdump，在 Mac 本地工具链做），平台侧如实标注不做假符号化。
 */
public interface LoganSymbolService {

    /** base64 内容落盘+入库；同 hash 已存在返回既有记录并 duplicate=true。 */
    Map<String, Object> upload(String appId, Integer platform, String appVersion, String buildVersion,
            String kind, String fileName, String contentBase64, String note, String uploader, String source);

    List<LoganSymbolDTO> list(String appId, String appVersion, String kind, Integer limit);

    LoganSymbolDTO get(long id);

    /** 落盘文件，不存在返回 null。 */
    File fileOf(LoganSymbolDTO dto);

    boolean delete(long id);

    /** 对该崩溃应用其版本最新 mapping：{hasMapping, symbolId, originalContent, changedFrames, totalFrames}。 */
    Map<String, Object> applyMapping(long crashId);
}
