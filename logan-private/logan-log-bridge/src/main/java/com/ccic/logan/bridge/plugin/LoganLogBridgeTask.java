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

    @Input
    public abstract ListProperty<String> getIncludePrefixes();

    @Input
    public abstract ListProperty<String> getExcludePrefixes();

    @Input
    public abstract ListProperty<String> getHardExcludePrefixes();

    @Input
    public abstract Property<Boolean> getBridgeEnabled();

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

    /** 命中改写条件才 transform；返回 null 表示未改写。 */
    private byte[] maybeTransform(String relPath, byte[] code) {
        if (!Boolean.TRUE.equals(getBridgeEnabled().get())) return null;
        if (!shouldRewrite(relPath)) return null;
        return transform(relPath, code);
    }

    /** 白名单命中且不在任何排除名单；相对路径按 VM 内部名比较。 */
    private boolean shouldRewrite(String relPath) {
        if (!relPath.endsWith(".class")) return false;
        String internal = relPath.substring(0, relPath.length() - ".class".length());
        for (String ex : getHardExcludePrefixes().get()) if (internal.startsWith(ex)) return false;
        for (String ex : getExcludePrefixes().get()) if (ex != null && !ex.isEmpty() && internal.startsWith(ex.replace('.', '/'))) return false;
        List<String> inc = getIncludePrefixes().get();
        if (inc.isEmpty()) return true;
        for (String in : inc) if (in != null && !in.isEmpty() && internal.startsWith(in.replace('.', '/'))) return true;
        return false;
    }

    /** 返回改写后的字节码；类里没有 Log 静态调用点返回 null。 */
    private byte[] transform(String relPath, byte[] code) {
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
                            if (opcode == Opcodes.INVOKESTATIC && ORIGINAL_OWNER.equals(owner)) {
                                super.visitMethodInsn(opcode, BRIDGE_OWNER, mName, mDesc, itf);
                                hit[0] = true;
                            } else {
                                super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                            }
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
