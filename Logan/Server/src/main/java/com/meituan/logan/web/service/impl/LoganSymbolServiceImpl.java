package com.meituan.logan.web.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.meituan.logan.web.dto.LoganCrashDTO;
import com.meituan.logan.web.dto.LoganSymbolDTO;
import com.meituan.logan.web.mapper.LoganCrashMapper;
import com.meituan.logan.web.mapper.LoganSymbolMapper;
import com.meituan.logan.web.service.LoganSymbolService;
import com.meituan.logan.web.util.SymbolFileUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 符号表归档 + Android mapping 反混淆。
 * mapping/sourcemap 是纯文本，平台侧能真正还原堆栈；dsym/so 只归档与提供下载，
 * 平台不伪造符号化结果（Mach-O/ELF 符号化需要 atos / ndk addr2line，在本地工具链做）。
 */
@Service
public class LoganSymbolServiceImpl implements LoganSymbolService {

    private static final Logger LOGGER = Logger.getLogger(LoganSymbolServiceImpl.class);

    /** 反混淆时单行最多扫描长度，防超长业务行把正则拖死。 */
    private static final int MAX_LINE_SCAN = 4000;

    /** mapping 文件解析上限。 */
    private static final long MAX_MAPPING_BYTES = 24L * 1024 * 1024;

    /** 堆栈里形如 com.foo.Bar.baz( 的符号引用。 */
    private static final Pattern FRAME_SYMBOL =
            Pattern.compile(
"([A-Za-z][\\w$]*(?:\\.[\\w$<>]+)+)(?=\\()");

    @Resource
    private LoganSymbolMapper symbolMapper;

    @Resource
    private LoganCrashMapper crashMapper;

    @Override
    public Map<String, Object> upload(String appId, Integer platform, String appVersion, String buildVersion,
            String kind, String fileName, String contentBase64, String note, String uploader, String source) {
        Map<String, Object> result = new HashMap<String, Object>();
        if (!SymbolFileUtil.isKind(kind)) {
            result.put(
"error", 
"kind must be mapping/dsym/so/sourcemap");
            return result;
        }
        String realKind = kind.trim().toLowerCase();
        SymbolFileUtil.Write w = SymbolFileUtil.write(contentBase64, appId + 
"-" + realKind, fileName);
        if (w == null) {
            result.put(
"error", 
"symbol content empty or too large (max 60MB)");
            return result;
        }
        LoganSymbolDTO exist = symbolMapper.selectByHash(appId, w.sha1);
        if (exist != null) {
            result.put(
"duplicate", Boolean.TRUE);
            result.put(
"symbol", exist);
            return result;
        }
        LoganSymbolDTO dto = new LoganSymbolDTO();
        dto.setAppId(appId);
        dto.setPlatform(platform == null ? 0 : platform.intValue());
        dto.setAppVersion(StringUtils.trimToNull(appVersion));
        dto.setBuildVersion(StringUtils.trimToNull(buildVersion));
        dto.setKind(realKind);
        dto.setFileName(StringUtils.abbreviate(StringUtils.defaultIfBlank(fileName, 
"symbol.bin"), 250));
        dto.setFilePath(w.fileName);
        dto.setFileHash(w.sha1);
        dto.setFileSize(w.size);
        dto.setNote(StringUtils.abbreviate(StringUtils.trimToNull(note), 500));
        dto.setUploader(StringUtils.abbreviate(StringUtils.defaultIfBlank(uploader, 
"console"), 64));
        dto.setSource(StringUtils.abbreviate(StringUtils.defaultIfBlank(source, 
"console"), 32));
        dto.setAddTime(System.currentTimeMillis());
        try {
            symbolMapper.insertIgnore(dto);
        } catch (Exception e) {
            LOGGER.warn(
"insert symbol failed: " + e.getMessage());
            result.put(
"error", 
"insert symbol failed: " + e.getMessage());
            return result;
        }
        result.put(
"symbol", dto);
        return result;
    }

    @Override
    public List<LoganSymbolDTO> list(String appId, String appVersion, String kind, Integer limit) {
        int n = limit == null ? 50 : Math.max(1, Math.min(200, limit.intValue()));
        try {
            List<LoganSymbolDTO> rows = symbolMapper.list(StringUtils.trimToNull(appId),
                    StringUtils.trimToNull(appVersion), StringUtils.trimToNull(kind), n);
            return rows == null ? Collections.<LoganSymbolDTO>emptyList() : rows;
        } catch (Exception e) {
            LOGGER.warn(
"list symbols failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public LoganSymbolDTO get(long id) {
        try {
            return symbolMapper.selectById(id);
        } catch (Exception e) {
            LOGGER.warn(
"get symbol failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public File fileOf(LoganSymbolDTO dto) {
        return dto == null ? null : SymbolFileUtil.resolve(dto.getFilePath());
    }

    @Override
    public boolean delete(long id) {
        LoganSymbolDTO dto = get(id);
        if (dto == null) {
            return false;
        }
        File f = fileOf(dto);
        if (f != null && !f.delete()) {
            LOGGER.warn(
"symbol file not deleted: " + f.getAbsolutePath());
        }
        try {
            return symbolMapper.deleteById(id) > 0;
        } catch (Exception e) {
            LOGGER.warn(
"delete symbol failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> applyMapping(long crashId) {
        Map<String, Object> result = new HashMap<String, Object>();
        LoganCrashDTO crash;
        try {
            crash = crashMapper.selectById(crashId);
        } catch (Exception e) {
            result.put(
"error", 
"load crash failed: " + e.getMessage());
            return result;
        }
        if (crash == null) {
            result.put(
"error", 
"crash not found: " + crashId);
            return result;
        }
        String kind = StringUtils.defaultString(crash.getKind()).toLowerCase();
        if (!
"java".equals(kind) && !
"anr".equals(kind) && !
"app".equals(kind)) {
            result.put(
"hasMapping", Boolean.FALSE);
            result.put(
"kind", kind);
            result.put(
"note", 
"该平台侧只对 java/anr/app 类崩溃做 mapping 反混淆；native 崩溃请用归档的 so/dSYM 在本地 addr2line / atos 符号化");
            return result;
        }
        LoganSymbolDTO mapping = symbolMapper.selectLatest(crash.getAppId(), crash.getAppVersion(), 
"mapping");
        if (mapping == null) {
            mapping = symbolMapper.selectLatest(crash.getAppId(), null, 
"mapping");
            if (mapping != null) {
                result.put(
"versionFallback", Boolean.TRUE);
            }
        }
        if (mapping == null) {
            result.put(
"hasMapping", Boolean.FALSE);
            result.put(
"note", 
"该应用还没有上传 mapping.txt（构建产物 app/build/outputs/mapping/<flavor>/mapping.txt）");
            return result;
        }
        File file = fileOf(mapping);
        if (file == null) {
            result.put(
"hasMapping", Boolean.FALSE);
            result.put(
"error", 
"mapping 文件在磁盘上不存在，可能符号目录未挂载");
            return result;
        }
        if (file.length() > MAX_MAPPING_BYTES) {
            result.put(
"hasMapping", Boolean.FALSE);
            result.put(
"error", 
"mapping 文件过大（" + file.length() + 
" bytes）");
            return result;
        }
        Map<String, String> classes = new HashMap<String, String>();
        Map<String, String> methods = new HashMap<String, String>();
        try {
            parseMapping(file, classes, methods);
        } catch (Exception e) {
            LOGGER.warn(
"parse mapping failed: " + e.getMessage());
            result.put(
"hasMapping", Boolean.FALSE);
            result.put(
"error", 
"parse mapping failed: " + e.getMessage());
            return result;
        }
        if (classes.isEmpty()) {
            result.put(
"hasMapping", Boolean.FALSE);
            result.put(
"error", 
"mapping 文件没有可解析的类映射（是不是传错了文件？）");
            return result;
        }
        Counter counter = new Counter();
        String content = deobfuscate(crash.getContent(), classes, methods, counter);
        String frames = deobfuscate(crash.getTopFrames(), classes, methods, counter);
        Map<String, Object> meta = new HashMap<String, Object>();
        meta.put(
"symbolId", Long.valueOf(mapping.getId()));
        meta.put(
"fileName", mapping.getFileName());
        meta.put(
"appVersion", mapping.getAppVersion());
        meta.put(
"buildVersion", mapping.getBuildVersion());
        meta.put(
"fileHash", mapping.getFileHash());
        meta.put(
"fileSize", Long.valueOf(mapping.getFileSize()));
        meta.put(
"uploader", mapping.getUploader());
        meta.put(
"addTime", Long.valueOf(mapping.getAddTime()));
        meta.put(
"classCount", Integer.valueOf(classes.size()));
        result.put(
"hasMapping", Boolean.TRUE);
        result.put(
"mapping", meta);
        result.put(
"originalContent", content);
        result.put(
"originalTopFrames", frames);
        result.put(
"totalFrames", Integer.valueOf(counter.total));
        result.put(
"changedFrames", Integer.valueOf(counter.changed));
        return result;
    }

    private static final class Counter {
        int total;
        int changed;
    }

    /** 解析 ProGuard/R8 mapping.txt 为 混淆名->原名 两张反查表。 */
    private static void parseMapping(File file, Map<String, String> classes, Map<String, String> methods)
            throws Exception {
        FileInputStream fis = new FileInputStream(file);
        BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
        String line;
        String currentObfClass = null;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }
                int arrow = line.indexOf(" -> ");
                if (arrow < 0) {
                    continue;
                }
                String left = line.substring(0, arrow).trim();
                String right = line.substring(arrow + 4).trim();
                boolean member = line.charAt(0) == ' ' || line.charAt(0) == '\t';
                if (!member) {
                    String orig = stripColon(left);
                    String obf = stripColon(right);
                    currentObfClass = obf;
                    if (StringUtils.isNotBlank(orig) && StringUtils.isNotBlank(obf) && !orig.equals(obf)) {
                        classes.put(obf, orig);
                    }
                    continue;
                }
                if (currentObfClass == null || left.indexOf(
"(") < 0) {
                    continue;
                }
                int paren = left.indexOf(
"(");
                String head = left.substring(0, paren).trim();
                int dotOrSpace = Math.max(head.lastIndexOf(
" "), head.lastIndexOf(
"."));
                String obfMethod = right.indexOf(
"(") > 0
                        ? right.substring(0, right.indexOf(
"(")).trim()
                        : right.trim();
                String origMethod = dotOrSpace < 0 ? head : head.substring(dotOrSpace + 1).trim();
                if (StringUtils.isBlank(obfMethod) || StringUtils.isBlank(origMethod)) {
                    continue;
                }
                String key = currentObfClass + 
"#" + obfMethod;
                if (!methods.containsKey(key)) {
                    // 重载会被混淆成同一个名字，取首个原名；平台如实标注可能不精确
                    methods.put(key, origMethod);
                }
            }
        } finally {
            try {
                reader.close();
            } catch (Exception ignore) {
            }
            try {
                fis.close();
            } catch (Exception ignore) {
            }
        }
    }

    private static String stripColon(String s) {
        String v = StringUtils.trimToEmpty(s);
        return v.endsWith(
":") ? v.substring(0, v.length() - 1).trim() : v;
    }

    /** 把堆栈文本里的 混淆类.混淆方法 换成原名，返回新文本并累计统计。 */
    private static String deobfuscate(String text, Map<String, String> classes, Map<String, String> methods,
            Counter counter) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        String[] lines = text.split(
"\n", -1);
        List<String> out = new ArrayList<String>(lines.length);
        boolean any = false;
        for (String raw : lines) {
            if (raw.length() > MAX_LINE_SCAN) {
                out.add(raw);
                continue;
            }
            Matcher m = FRAME_SYMBOL.matcher(raw);
            StringBuffer sb = new StringBuffer();
            boolean lineChanged = false;
            while (m.find()) {
                String fq = m.group(1);
                counter.total++;
                int dot = fq.lastIndexOf(
".");
                if (dot <= 0) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(fq));
                    continue;
                }
                String cls = fq.substring(0, dot);
                String member = fq.substring(dot + 1);
                String origCls = classes.get(cls);
                if (origCls == null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(fq));
                    continue;
                }
                String origMember = methods.get(cls + 
"#" + member);
                String replacement = origCls + 
"." + (origMember == null ? member : origMember);
                if (!replacement.equals(fq)) {
                    counter.changed++;
                    lineChanged = true;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            if (lineChanged) {
                any = true;
            }
            out.add(sb.toString());
        }
        return any ? joinLines(out) : text;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(
"\n");
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
