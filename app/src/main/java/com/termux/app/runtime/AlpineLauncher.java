package com.termux.app.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the PRoot command lines for Alpine. This is the ONE place the
 * argv lives, so an on-device failure can be fixed with a single patch.
 *
 * <p>Shipped argv (minimal argv, proven in NobiIDE via ProcessBuilder on
 * this phone 2026-10-04, where apk worked):</p>
 * <pre>
 * proot -r &lt;rootfs&gt; -b /sdcard:/sdcard -b /storage:/storage -w /root
 *       /bin/sh -c "if [ -x /bin/bash ]; then exec /bin/bash --login; else exec /bin/sh; fi"
 * </pre>
 * <p>The session shell runs through a {@code /bin/sh -c} wrapper that
 * replaces itself with the login shell via {@code exec} (same PID, same
 * PTY). The wrapper prints no text of its own: the Termux-style welcome
 * (MOTD) is printed for every login shell by
 * {@code /etc/profile.d/nobi-motd.sh} (written by the installer), and
 * the Termux-style prompt ({@code ~ $}) comes from the default
 * {@code ~/.bashrc} PS1 (see TERMUX_STYLE_SPEC.md). The wrapper falls
 * back to {@code /bin/sh} when Bash is absent instead of exiting.</p>
 *
 * <p>History: M2 shipped a fuller argv with a fake-root flag and extra
 * system binds. On Sam's phone (M2.1, 2026-10-31) steps 1-6 passed and
 * the Bash one-shot failed at apk update with Permission denied opening
 * the package index. M2.3 switches both the session and one-shot
 * commands to this minimal argv, the only material difference from the
 * proven NobiIDE argv, before trying anything else. M2.4 removes the
 * launcher welcome snippet so the MOTD is the only welcome text,
 * exactly once per new session.</p>
 */
public final class AlpineLauncher {

    /** Session fallback: Bash when installed, otherwise BusyBox sh. No printed text — the Termux-style welcome (MOTD) is printed by /etc/profile.d/nobi-motd.sh for login shells (see TERMUX_STYLE_SPEC.md). */
    private static final String SESSION_SHELL_COMMAND =
            "if [ -x /bin/bash ]; then exec /bin/bash --login; else exec /bin/sh; fi";

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
        argv.addAll(AlpineMountManager.buildBindArguments());
        argv.add("-w");
        argv.add("/root");
        argv.add("/bin/sh");
        argv.add("-c");
        argv.add(SESSION_SHELL_COMMAND);
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
        argv.addAll(AlpineMountManager.buildBindArguments());
        argv.add("-w");
        argv.add("/root");
        argv.add("/bin/sh");
        argv.add("-c");
        argv.add(command);
        return argv.toArray(new String[0]);
    }
}
