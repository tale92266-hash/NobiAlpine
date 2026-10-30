package com.termux.app.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the PRoot command lines for Alpine. This is the ONE place the
 * argv lives, so an on-device failure can be fixed with a single patch.
 *
 * <p>Shipped argv (proot-distro style):</p>
 * <pre>
 * proot -r &lt;rootfs&gt; -0 -b /dev -b /proc -b /sys
 *       -b /sdcard:/sdcard -b /storage:/storage -w /root
 *       /bin/sh -c "&lt;welcome&gt;; if [ -x /bin/bash ]; then exec /bin/bash --login; else ...; exec /bin/sh; fi"
 * </pre>
 * <p>The session shell runs through a {@code /bin/sh -c} wrapper so the
 * short welcome block prints before the login shell replaces the wrapper
 * with {@code exec} (same PID, same PTY). The wrapper falls back to
 * {@code /bin/sh} when Bash is absent instead of exiting.</p>
 *
 * <p>Documented fallback (proven in NobiIDE via ProcessBuilder, NOT shipped
 * here): minimal argv without {@code -0} and without the
 * {@code /dev}/{@code /proc}/{@code /sys} binds, {@code /bin/sh} directly,
 * {@code /sdcard} and {@code /storage} binds only. If Sam's on-device test
 * fails with the shipped argv, try the minimal argv before anything else.</p>
 */
public final class AlpineLauncher {

    private static final String WELCOME_SNIPPET =
            "printf 'Alpine Linux 3.24.3 (aarch64) via PRoot\\n"
                    + "Use apk update and apk add to install packages.\\n"
                    + "Shared storage: /sdcard\\n'; "
                    + "if [ -x /bin/bash ]; then exec /bin/bash --login; "
                    + "else printf 'Bash is not installed. Using sh.\\n'; exec /bin/sh; fi";

    private AlpineLauncher() {
    }

    /** Guest shell: Bash when installed in the rootfs, otherwise BusyBox sh. */
    public static String getGuestShell(Context context) {
        File bash = new File(AlpineInstaller.getRootfsDir(context), "bin/bash");
        return bash.exists() ? "/bin/bash" : "/bin/sh";
    }

    /** Full session argv, including the PRoot binary as argv[0]. */
    public static String[] buildSessionCommand(Context context) {
        String proot = AlpineInstaller.getProotPath(context);
        List<String> argv = new ArrayList<>();
        argv.add(proot);
        argv.add("-r");
        argv.add(AlpineInstaller.getRootfsDir(context).getAbsolutePath());
        argv.add("-0");
        argv.addAll(AlpineMountManager.buildBindArguments());
        argv.add("-w");
        argv.add("/root");
        argv.add("/bin/sh");
        argv.add("-c");
        argv.add(WELCOME_SNIPPET);
        return argv.toArray(new String[0]);
    }

    /** Session arguments for ExecutionCommand (argv without argv[0]). */
    public static String[] buildSessionArguments(Context context) {
        String[] command = buildSessionCommand(context);
        String[] args = new String[command.length - 1];
        System.arraycopy(command, 1, args, 0, args.length);
        return args;
    }

    /** One-shot argv: {@code /bin/sh -c <command>} inside the rootfs. */
    public static String[] buildOneShotCommand(Context context, String command) {
        String proot = AlpineInstaller.getProotPath(context);
        List<String> argv = new ArrayList<>();
        argv.add(proot);
        argv.add("-r");
        argv.add(AlpineInstaller.getRootfsDir(context).getAbsolutePath());
        argv.add("-0");
        argv.addAll(AlpineMountManager.buildBindArguments());
        argv.add("-w");
        argv.add("/root");
        argv.add("/bin/sh");
        argv.add("-c");
        argv.add(command);
        return argv.toArray(new String[0]);
    }
}
