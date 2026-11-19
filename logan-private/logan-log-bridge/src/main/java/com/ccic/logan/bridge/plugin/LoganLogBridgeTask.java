package com.ccic.logan.bridge.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * ASM 改写任务：INVOKESTATIC android/util/Log.* -> com/ccic/logan/bridge/LoganLogBridge.*
 * （桥方法签名与 Log 完全一致，desc 不变，只换 owner）。
 * toTransform 语义：吃 PROJECT 作用域全部 class 输入（jar+目录），合并写出单个产物 jar。
 */
public abstract class LoganLogBridgeTask extends DefaultTask {

    private static final String ORIGINAL_OWNER = "android/util/Log";
    private static final String BRIDGE_OWNER = "com/ccic/logan/bridge/LoganLogBridge";

    /** Only take over these signatures that the runtime class really provides (method name + descriptor, matching LoganLogBridge's javap output).
     *  Other static methods of android.util.Log (e.g. getStackTraceString, which business code does call) must stay untouched,
     *  otherwise the rewritten bytecode would call a missing method and crash with NoSuchMethodError at runtime. */
    private static final Set<String> BRIDGE_SIGNATURES = new HashSet<String>(java.util.Arrays.asList(
            "v(Ljava/lang/String;Ljava/lang/String;)I",
            "v(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I",
            "v(Ljava/lang/String;Ljava/lang/Throwable;)I",
            "d(Ljava/lang/String;Ljava/lang/String;)I",
            "d(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I",
            "i(Ljava/lang/String;Ljava/lang/String;)I",
            "i(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I",
            "w(Ljava/lang/String;Ljava/lang/String;)I",
            "w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I",
            "w(Ljava/lang/String;Ljava/lang/Throwable;)I",
            "e(Ljava/lang/String;Ljava/lang/String;)I",
            "e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I",
            "println(ILjava/lang/String;Ljava/lang/String;)I"));

    // ---- 通用网络观测规则（只匹配调用点，插件本身不 import okhttp3/android.webkit）----
    /** okhttp3.OkHttpClient$Builder#build() 调用点：改走 LoganOkHttpInstaller.build(Object) 静态跳板。 */
    private static final String OKHTTP_BUILDER = "okhttp3/OkHttpClient$Builder";
    private static final String OKHTTP_BUILD_DESC = "()Lokhttp3/OkHttpClient;";
    private static final String OKHTTP_INSTALLER = "com/ccic/logan/http/LoganOkHttpInstaller";
    /** WebView#setWebViewClient(...) 调用点：改走 LoganWebViewInstaller 静态跳板（owner 不限，兼容 TBS/UC 内核）。 */
    private static final String WEBVIEW_OWNER = "android/webkit/WebView";
    private static final String WEBVIEW_SET_CLIENT = "setWebViewClient";
    private static final String WEBVIEW_SET_DESC = "(Landroid/webkit/WebViewClient;)V";
    private static final String WEBVIEW_INSTALLER = "com/ccic/logan/http/LoganWebViewInstaller";

    // ---- 行为轨迹采集规则（编译期把 setOnClickListener/setOnLongClickListener 换成包装跳板）----
    /** 静态接管跳板：参数类型 Object，栈形状与原 INVOKEVIRTUAL 等价（两引用 + void），零新增指令。 */
    private static final String CLICK_INSTALLER = "com/ccic/logan/behavior/LoganClickInstaller";
    private static final String CLICK_SET_LISTENER = "setOnClickListener";
    private static final String CLICK_SET_LONG_LISTENER = "setOnLongClickListener";

    // ---- 非致命错误采集规则（编译期给每个「吞掉异常」的 catch handler 入口装上报跳板）----
    /** catch handler 入口注入的静态跳板：DUP + LDC where + INVOKESTATIC report(Throwable,String)，栈增量为 0。 */
    private static final String CATCH_BRIDGE = "com/ccic/logan/bridge/LoganCatchBridge";
    private static final String CATCH_REPORT = "report";
    private static final String CATCH_DESC = "(Ljava/lang/Throwable;Ljava/lang/String;)V";
    /** 单类最多注入这么多 handler，防极端类膨胀。 */
    private static final int CATCH_MAX_PER_CLASS = 400;

    @Input
    public abstract ListProperty<String> getIncludePrefixes();

    @Input
    public abstract ListProperty<String> getExcludePrefixes();

    @Input
    public abstract ListProperty<String> getHardExcludePrefixes();

    @Input
    public abstract Property<Boolean> getBridgeEnabled();

    /** 网络观测规则独立开关（默认开，插件绑到 extension.networkEnabled）。 */
    @Input
    public abstract Property<Boolean> getNetworkEnabled();

    /** 非致命错误采集规则独立开关（默认开，插件绑到 extension.catchEnabled；关掉只是不再注入，SDK 侧另有 LoganError.setEnabled 一行关整条通道）。 */
    @Input
    public abstract Property<Boolean> getCatchEnabled();

    /** 行为轨迹钩子规则独立开关（默认开，插件绑到 extension.behaviorEnabled；SDK 侧另有 LoganBehavior.setEnabled 一行关整条通道）。 */
    @Input
    public abstract Property<Boolean> getBehaviorEnabled();

    @InputFiles
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<org.gradle.api.file.RegularFile> getAllJars();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<Directory> getAllDirectories();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    @TaskAction
    public void rewrite() throws Exception {
        File out = getOutput().get().getAsFile();
        getProject().delete(out);
        Files.createDirectories(out.toPath().getParent());
        Set<String> written = new HashSet<>();
        int scanned = 0, touched = 0;
        try (JarOutputStream jo = new JarOutputStream(new FileOutputStream(out))) {
            // 1) 输入 jar（目录编译模式关闭时会出现）：解包重放
            for (org.gradle.api.file.RegularFile rf : getAllJars().get()) {
                File src = rf.getAsFile();
                try (JarFile jf = new JarFile(src)) {
                    Enumeration<JarEntry> en = jf.entries();
                    while (en.hasMoreElements()) {
                        JarEntry e = en.nextElement();
                        if (e.isDirectory() || !written.add(e.getName())) continue;
                        String rel = e.getName();
                        byte[] code = readAll(jf.getInputStream(e));
                        byte[] newer = maybeTransform(rel, code);
                        jo.putNextEntry(new JarEntry(rel));
                        jo.write(newer != null ? newer : code);
                        if (newer != null) { touched++; scanned++; }
                        jo.closeEntry();
                    }
                }
            }
            // 2) 工程 class 目录
            for (Directory dir : getAllDirectories().get()) {
                File root = dir.getAsFile();
                List<File> classFiles = new ArrayList<>();
                collect(root, classFiles);
                for (File cf : classFiles) {
                    String rel = root.toPath().relativize(cf.toPath()).toString().replace(File.separatorChar, '/');
                    if (!written.add(rel)) continue;
                    scanned++;
                    byte[] code = Files.readAllBytes(cf.toPath());
                    byte[] newer = maybeTransform(rel, code);
                    jo.putNextEntry(new JarEntry(rel));
                    if (newer != null) { jo.write(newer); touched++; }
                    else jo.write(code);
                    jo.closeEntry();
                }
            }
        }
        final int s = scanned, t = touched;
        getLogger().lifecycle("logan-log-bridge: scanned {} classes, rewrote {}", s, t);
    }

    /** 命中改写条件才改写；返回 null 表示未改写。catch 规则先跑（树模式，要看到整段 handler 才能判定），再跑调用点规则。 */
    private byte[] maybeTransform(String relPath, byte[] code) {
        boolean logOn = Boolean.TRUE.equals(getBridgeEnabled().get());
        boolean netOn = Boolean.TRUE.equals(getNetworkEnabled().get());
        boolean catchOn = Boolean.TRUE.equals(getCatchEnabled().get());
        boolean behOn = Boolean.TRUE.equals(getBehaviorEnabled().get());
        if (!logOn && !netOn && !catchOn && !behOn) return null;
        if (!shouldRewrite(relPath)) return null;
        byte[] cur = code;
        if (catchOn) {
            byte[] c = transformCatch(relPath, cur);
            if (c != null) {
                cur = c;
            }
        }
        if (logOn || netOn || behOn) {
            byte[] b = transform(relPath, cur, logOn, netOn, behOn);
            if (b != null) {
                cur = b;
            }
        }
        return cur == code ? null : cur;
    }

    /**
     * catch handler 注入（ASM 树模式）。只在「确实吞掉了异常」的 handler 上装跳板：
     *   1) finally（type == null）跳过 —— handler 入口栈上没有异常引用，注入会 VerifyError；
     *   2) handler 体内出现 ATHROW（记录后继续抛 / try-with-resources 的收尾重抛）跳过 —— 这类最终由崩溃通道覆盖，
     *      再计入错误分析会让同一事件在两个页面各算一次；
     *   3) handler 已经自己调过 LoganCatchBridge / LoganError（业务显式上报过）跳过，避免双报；
     *   4) 注入形状 DUP + LDC + INVOKESTATIC，操作数栈净变化为 0：入口帧仍是 [Throwable]，
     *      DUP 成 [T,T]，跳板消耗 (Throwable,String) 后仍留 [T]，原有 StackMapTable 帧依旧成立；
     *      但 maxStack 需要 +1，故本趟用 ClassWriter(COMPUTE_MAXS)（只算大小，不重算帧）。
     * 稳定性红线：整趟包 try/catch，任何异常都退化为「不改写」，绝不让编译或运行失败。
     */
    private byte[] transformCatch(String relPath, byte[] code) {
        try {
            ClassReader cr = new ClassReader(code);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            if (cn.methods == null || cn.methods.isEmpty()) {
                return null;
            }
            String where = cn.name + "#";
            int injected = 0;
            boolean changed = false;
            for (MethodNode mn : cn.methods) {
                if (mn.tryCatchBlocks == null || mn.tryCatchBlocks.isEmpty() || mn.instructions == null) {
                    continue;
                }
                for (Object tObj : mn.tryCatchBlocks) {
                    if (injected >= CATCH_MAX_PER_CLASS) {
                        break;
                    }
                    TryCatchBlockNode tcb = (TryCatchBlockNode) tObj;
                    if (tcb.type == null || tcb.handler == null || tcb.end == null) {
                        continue; // finally：栈上没有异常
                    }
                    if (!handlerInjectable(mn, tcb)) {
                        continue;
                    }
                    InsnList add = new InsnList();
                    add.add(new InsnNode(Opcodes.DUP));
                    add.add(new LdcInsnNode(where + mn.name));
                    add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CATCH_BRIDGE, CATCH_REPORT, CATCH_DESC, false));
                    // 紧跟 handler 起始标签之后插入：帧挂在标签上，注入发生在帧之后，校验器看到的入口状态不变
                    mn.instructions.insert(tcb.handler, add);
                    injected++;
                    changed = true;
                }
            }
            if (!changed) {
                return null;
            }
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable e) {
            getLogger().warn("logan-log-bridge catch-rule skip {}: {}", relPath, e.toString());
            return null;
        }
    }

    /** handler 是否该注入：无 ATHROW、未自报、且不是一片空（空 handler 也报，那正是「静默吞异常」最有价值的一类）。 */
    private static boolean handlerInjectable(MethodNode mn, TryCatchBlockNode tcb) {
        AbstractInsnNode p = firstRealInsn(mn, tcb.handler);
        if (p == null) {
            return true; // handler 里只有标签/伪指令 = 纯静默吞异常，正是要采的一类
        }
        AbstractInsnNode stop = tcb.end;
        int walked = 0;
        boolean sawAny = false;
        while (p != null && p != stop && walked < 8000) {
            int type = p.getType();
            if (type == AbstractInsnNode.METHOD_INSN) {
                MethodInsnNode mi = (MethodInsnNode) p;
                String owner = mi.owner;
                if (owner != null && (owner.equals(CATCH_BRIDGE)
                        || owner.equals("com/ccic/logan/error/LoganError"))) {
                    return false; // 业务已经自己上报过，别双报
                }
                sawAny = true;
            } else if (type == AbstractInsnNode.JUMP_INSN) {
                JumpInsnNode ji = (JumpInsnNode) p;
                if (ji.label != null && containsLabel(tcb, ji.label)) {
                    // 从 handler 外面跳进来的目标不影响；这里只关心 handler 内部的跳转，无需处理
                    sawAny = true;
                }
            } else if (type == AbstractInsnNode.INSN) {
                if (((InsnNode) p).getOpcode() == Opcodes.ATHROW) {
                    return false; // 记录后继续抛：交给崩溃通道
                }
                sawAny = true;
            } else if (type == AbstractInsnNode.FIELD_INSN || type == AbstractInsnNode.TYPE_INSN
                    || type == AbstractInsnNode.VAR_INSN || type == AbstractInsnNode.LDC_INSN) {
                sawAny = true;
            }
            p = p.getNext();
            walked++;
        }
        return true; // 空 handler 同样注入（静默吞异常正是要点），sawAny 仅用于调试语义
    }

    /** 从 handler 标签往后取第一条真实指令（跳过 Label/LineNumber/Frame 等伪指令，它们 getOpcode() 返回 -1）。 */
    private static AbstractInsnNode firstRealInsn(MethodNode mn, LabelNode start) {
        AbstractInsnNode n = start;
        int guard = 0;
        while (n != null && guard++ < 200) {
            if (n.getOpcode() >= 0) {
                return n;
            }
            n = n.getNext();
        }
        return null;
    }

    private static boolean containsLabel(TryCatchBlockNode tcb, LabelNode ln) {
        return ln == tcb.handler || ln == tcb.end || ln == tcb.start;
    }

    /** 白名单命中且不在任何排除名单；相对路径按 VM 内部名比较。 */
    private boolean shouldRewrite(String relPath) {
        if (!relPath.endsWith(".class")) return false;
        // 依赖 jar（Scope.ALL）里混着元数据与 Kotlin 元文件：不是待改写类，直接跳过
        if (relPath.startsWith("META-INF/") || relPath.startsWith("kotlin/")) return false;
        if (relPath.endsWith("module-info.class") || relPath.endsWith("package-info.class")) return false;
        String internal = relPath.substring(0, relPath.length() - ".class".length());
        for (String ex : getHardExcludePrefixes().get()) if (internal.startsWith(ex)) return false;
        for (String ex : getExcludePrefixes().get()) if (ex != null && !ex.isEmpty() && internal.startsWith(ex.replace('.', '/'))) return false;
        List<String> inc = getIncludePrefixes().get();
        if (inc.isEmpty()) return true;
        for (String in : inc) if (in != null && !in.isEmpty() && internal.startsWith(in.replace('.', '/'))) return true;
        return false;
    }

    /**
     * 判定「单参数 setWebViewClient」：接受 android.webkit.WebViewClient 或任意内核的同名自绘类
     * （形如 (Lcom/tencent/smtt/sdk/WebViewClient;)V），只要参数类型名以 WebViewClient; 结尾就算。
     * owner 不参与判定，因此宿主用哪个内核都能装上；调用者自身已在 HARD_EXCLUDE 里排除。
     */
    private static boolean isWebViewClientSetterDesc(String mDesc) {
        if (mDesc == null || mDesc.length() < 12) {
            return false;
        }
        if (!mDesc.endsWith(";)V") || mDesc.charAt(0) != '(') {
            return false;
        }
        if (mDesc.indexOf('(', 1) >= 0) {
            return false;
        }
        String arg = mDesc.substring(1, mDesc.length() - 3);
        if (arg.length() < 1 || arg.charAt(0) != 'L') {
            return false;
        }
        String type = arg.substring(1);
        return type.endsWith("WebViewClient");
    }

    /**
     * 判定「单参数监听器 setter」：只认以 OnClickListener / OnLongClickListener 结尾的监听器类型，
     * owner 不参与判定，故各内核与自绘控件同样能装上。调用方自身已在 HARD_EXCLUDE
     * （com/ccic/logan/behavior/）内排除，跳板内部再调 setter 不会递归。
     */
    private static boolean isListenerSetterDesc(String mDesc) {
        if (mDesc == null || mDesc.length() < 20) {
            return false;
        }
        if (!mDesc.endsWith(";)V") || mDesc.charAt(0) != '(') {
            return false;
        }
        if (mDesc.indexOf('(', 1) >= 0) {
            return false;
        }
        String arg = mDesc.substring(1, mDesc.length() - 3);
        if (arg.length() < 2 || arg.charAt(0) != 'L') {
            return false;
        }
        String type = arg.substring(1);
        return type.endsWith("OnClickListener") || type.endsWith("OnLongClickListener");
    }

    /** 返回改写后的字节码；类里没有 Log 静态调用点返回 null。 */
    private byte[] transform(String relPath, byte[] code, boolean logOn, boolean netOn, boolean behOn) {
        try {
            ClassReader cr = new ClassReader(code);
            ClassWriter cw = new ClassWriter(0);
            final boolean[] hit = {false};
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                            if (logOn && opcode == Opcodes.INVOKESTATIC && ORIGINAL_OWNER.equals(owner)
                                    && mName != null && BRIDGE_SIGNATURES.contains(mName + mDesc)) {
                                super.visitMethodInsn(opcode, BRIDGE_OWNER, mName, mDesc, itf);
                                hit[0] = true;
                                return;
                            }
                            if (netOn && opcode == Opcodes.INVOKEVIRTUAL && !itf
                                    && OKHTTP_BUILDER.equals(owner) && "build".equals(mName)
                                    && OKHTTP_BUILD_DESC.equals(mDesc)) {
                                // 栈形状完全等价：旧 INVOKEVIRTUAL 弹 builder 压 client；新 INVOKESTATIC build(Object) 同样弹 builder(Object) 压 Object，
                                // 再 CHECKCAST 回 okhttp3/OkHttpClient。深度不变、不新增分支目标，原有 StackMapTable frame 仍成立（此前多写的 POP 会把操作数栈打空，
                                // 导致 INVOKESTATIC 错拿下层操作数，ART 校验器会报 VerifyError，已删）；也不动 maxStack。
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, OKHTTP_INSTALLER, "build", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "okhttp3/OkHttpClient");
                                hit[0] = true;
                                return;
                            }
                            if (netOn && opcode == Opcodes.INVOKEVIRTUAL && !itf
                                    && WEBVIEW_SET_CLIENT.equals(mName)
                                    && isWebViewClientSetterDesc(mDesc)) {
                                // 参数个数与顺序完全一致，仅换为静态跳板，零新增指令。描述符必须写 (Object,Object)V：调用点可能是任意内核（TBS com.tencent.smtt.sdk.WebView / UC 等，均非 android.webkit.WebView 子类），若写 android.webkit 类型 ART 校验器会以「register has type BridgeWebView but expected android.webkit.WebView」拒绝整个类；跳板 LoganWebViewInstaller.setWebViewClient(Object,Object) 本身就是 Object 形参。
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, WEBVIEW_INSTALLER, WEBVIEW_SET_CLIENT,
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                hit[0] = true;
                                return;
                            }
                            if (behOn && opcode == Opcodes.INVOKEVIRTUAL && !itf
                                    && (CLICK_SET_LISTENER.equals(mName) || CLICK_SET_LONG_LISTENER.equals(mName))
                                    && isListenerSetterDesc(mDesc)) {
                                // 与原调用点栈形状等价（view + listener 两个引用操作数、void 返回），不新增指令、不动 maxStack。
                                // 描述符用 (Object,Object)V：各内核与自绘控件各自声明同名 setter，其监听器类型并非 android.view 的接口，
                                // 写具体接口类型会被 ART 校验器按 register 实际类型拒绝整个类；跳板形参本身就是 Object。
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, CLICK_INSTALLER, mName,
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                hit[0] = true;
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                        }
                    };
                }
            }, 0);
            return hit[0] ? cw.toByteArray() : null;
        } catch (Exception e) {
            getLogger().warn("logan-log-bridge skip {}: {}", relPath, e.toString());
            return null;
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".class")) out.add(f);
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try (in; java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
