package com.termux.app.runtime;

import android.content.Context;
import android.os.Build;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The environment for processes running inside Alpine.
 *
 * <p>Allow-list only: HOME, PATH, TERM, LANG, TMPDIR and the PRoot
 * variables. No Termux {@code PREFIX} / {@code TERMUX__*} variables may
 * leak in — Alpine has no $PREFIX, and leaked Termux paths break tools
 * that read the environment. No {@code LD_PRELOAD} either: the JNI
 * subprocess path already starts from a clean environment, and the
 * one-shot path clears the inherited environment before applying this map.</p>
 */
public final class AlpineEnvironment {

    public static final String ALPINE_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    public static final String GUEST_HOME = "/root";

    private AlpineEnvironment() {
    }

    /** Writable tmp dir for PRoot (the default /tmp is not writable on Android). Created on demand. */
    public static File getProotTmpDir(Context context) {
        File tmpDir = new File(context.getCacheDir(), "proot-tmp");
        tmpDir.mkdirs();
        return tmpDir;
    }

    public static Map<String, String> buildEnvironmentMap(Context context) {
        File prootTmp = getProotTmpDir(context);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("HOME", GUEST_HOME);
        env.put("PATH", ALPINE_PATH);
        env.put("LANG", "en_US.UTF-8");
        env.put("TMPDIR", prootTmp.getAbsolutePath());
        env.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        String loader = AlpineInstaller.getProotLoaderPath(context);
        if (loader != null) env.put("PROOT_LOADER", loader);
        env.put("ANDROID__BUILD_VERSION_SDK", String.valueOf(Build.VERSION.SDK_INT));
        return env;
    }

    /** Environment as "NAME=value" strings for the JNI subprocess path. */
    public static String[] buildEnvironment(Context context, boolean isFailsafe, String workingDirectory) {
        // The Alpine client is never used for failsafe sessions; the flag is
        // accepted only to satisfy the ShellEnvironmentClient interface.
        // Make sure the host working directory exists so the JNI chdir()
        // cannot fail on a missing directory.
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            File wd = new File(workingDirectory);
            String filesPath = TermuxConstants.TERMUX_FILES_DIR_PATH;
            if (workingDirectory.startsWith(filesPath)) wd.mkdirs();
        }
        File hostHome = AlpineInstaller.getHostHomeDir(context);
        hostHome.mkdirs();
        getProotTmpDir(context);

        Map<String, String> map = buildEnvironmentMap(context);
        String[] env = new String[map.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            env[i++] = entry.getKey() + "=" + entry.getValue();
        }
        return env;
    }
}
