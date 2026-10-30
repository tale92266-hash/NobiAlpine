package com.termux.app.runtime;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Downloads, verifies and extracts the Alpine Linux minirootfs into private
 * app storage, then installs Bash inside it with a one-shot PRoot run.
 *
 * <p>Adapted from the proven NobiIDE {@code LinuxInstaller} (PRoot + Alpine
 * 3.24.2, verified on device 2026-10-04): same download/extraction approach
 * (system tar first, pure-Java tar.gz fallback with symlink handling, zip-slip
 * guard) and same static resolv.conf for DNS. Differences, all deliberate:
 * Alpine is pinned to 3.24.3, the SHA-256 checksum is fetched at install time
 * and verified before extraction (never hardcoded, never skipped), Bash is
 * installed via PRoot (minirootfs ships only BusyBox sh), and PRoot is never
 * downloaded at runtime — it must be the bundled native library.</p>
 *
 * <p>All long work runs on the caller's thread; callers must use a background
 * thread. A failed step deletes only its partial output and the install can
 * be re-run from the start — completed steps are skipped when their output
 * is already present and verified.</p>
 */
public final class AlpineInstaller {

    private static final String LOG_TAG = "AlpineInstaller";

    public static final String ALPINE_VERSION = "3.24.3";
    public static final String ALPINE_ARCH = "aarch64";
    public static final String ALPINE_FILE_NAME = "alpine-minirootfs-3.24.3-aarch64.tar.gz";
    public static final String ALPINE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/" + ALPINE_FILE_NAME;

    /** Termux-style welcome text (see TERMUX_STYLE_SPEC.md). Written to /etc/motd on every fresh extract. */
    private static final String MOTD_TEXT =
            "Welcome to Nobi Alpine!\n"
                    + "Docs:       https://termux.dev/docs\n"
                    + "Donate:     https://termux.dev/donate\n"
                    + "Community:  https://termux.dev/community\n"
                    + "Working with packages:\n"
                    + " - Search:  apk search <query>\n"
                    + " - Install: apk add <package>\n"
                    + " - Upgrade: apk upgrade\n"
                    + "Report issues at https://termux.dev/issues\n";

    public static final String STEP_CHECK = "check";
    public static final String STEP_PROOT = "proot";
    public static final String STEP_DOWNLOAD = "download";
    public static final String STEP_VERIFY = "verify";
    public static final String STEP_EXTRACT = "extract";
    public static final String STEP_REPOS = "repos";
    public static final String STEP_BASH = "bash";

    /** Progress callback. {@code percent} is 0-100, or -1 when indeterminate. */
    public interface ProgressListener {
        void onStep(String stepId, int percent, String detail);
    }

    /** Result of {@link #install(Context, ProgressListener)}. */
    public static final class InstallResult {
        public final boolean ok;
        public final String failedStep;
        public final String error;

        private InstallResult(boolean ok, String failedStep, String error) {
            this.ok = ok;
            this.failedStep = failedStep;
            this.error = error;
        }

        public static InstallResult success() {
            return new InstallResult(true, null, null);
        }

        public static InstallResult failure(String stepId, String error) {
            return new InstallResult(false, stepId, error);
        }
    }

    private AlpineInstaller() {
    }

    public static boolean isArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    public static File getAlpineDir(Context context) {
        return new File(context.getFilesDir(), "alpine");
    }

    public static File getRootfsDir(Context context) {
        return new File(getAlpineDir(context), "rootfs");
    }

    public static File getDownloadDir(Context context) {
        return new File(getAlpineDir(context), "downloads");
    }

    public static File getMarkerFile(Context context) {
        return new File(getAlpineDir(context), ".installed");
    }

    /** Host-side home directory used as the chdir target for Alpine sessions. */
    public static File getHostHomeDir(Context context) {
        return new File(getAlpineDir(context), "home");
    }

    /**
     * PRoot binary path. The bundled native library in
     * {@code nativeLibraryDir} is the only source: executing from there
     * bypasses the Android 10+ W^X restriction on executing from
     * {@code files/} (proven in NobiIDE). Returns null when missing or
     * not executable — callers must fail honestly instead of downloading.
     */
    public static String getProotPath(Context context) {
        File nativeLib = new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
        if (nativeLib.exists() && nativeLib.canExecute()) return nativeLib.getAbsolutePath();
        return null;
    }

    /** PRoot loader path, or null when the bundled loader is missing. */
    public static String getProotLoaderPath(Context context) {
        File loader = new File(context.getApplicationInfo().nativeLibraryDir, "libproot_loader.so");
        if (loader.exists()) return loader.getAbsolutePath();
        return null;
    }

    /** True only when the marker matches this Alpine version, /bin/sh exists in the rootfs, and PRoot is present. */
    public static boolean isInstalled(Context context) {
        if (getProotPath(context) == null) return false;
        File marker = getMarkerFile(context);
        if (!marker.exists()) return false;
        if (!ALPINE_VERSION.equals(readMarkerVersion(marker))) return false;
        File rootfs = getRootfsDir(context);
        return new File(rootfs, "bin/sh").exists()
                || new File(rootfs, "usr/bin/sh").exists()
                || new File(rootfs, "bin/busybox").exists()
                || new File(rootfs, "usr/bin/busybox").exists();
    }

    /** True when a rootfs directory exists but the install is not valid (partial or corrupt install). */
    public static boolean hasPartialRootfs(Context context) {
        return getRootfsDir(context).exists() && !isInstalled(context);
    }

    public static String getInstalledVersion(Context context) {
        return readMarkerVersion(getMarkerFile(context));
    }

    private static String readMarkerVersion(File marker) {
        if (!marker.exists()) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(marker), "UTF-8"));
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Delete the rootfs and marker (reinstall). The verified download is kept. */
    public static void deleteRootfs(Context context) {
        deleteRecursively(getRootfsDir(context));
        getMarkerFile(context).delete();
    }

    public static InstallResult install(Context context, ProgressListener listener) {
        // Step 1: device check.
        report(listener, STEP_CHECK, 0, "Checking device");
        if (!isArm64()) {
            return InstallResult.failure(STEP_CHECK,
                    "This device is not arm64 (ARM64). Alpine for ARM64 cannot run here.");
        }
        report(listener, STEP_CHECK, 100, "ARM64 device");

        // Step 2: bundled PRoot check. No runtime download, ever.
        report(listener, STEP_PROOT, 0, "Checking PRoot");
        if (getProotPath(context) == null) {
            return InstallResult.failure(STEP_PROOT,
                    "The bundled PRoot binary is missing. Reinstall the app.");
        }
        report(listener, STEP_PROOT, 100, "PRoot ready");

        File downloadDir = getDownloadDir(context);
        downloadDir.mkdirs();
        File tarFile = new File(downloadDir, ALPINE_FILE_NAME);

        // Fetch the matching checksum at install time. Never hardcoded.
        String expectedSha256;
        try {
            expectedSha256 = fetchChecksum();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Checksum fetch failed", e);
            return InstallResult.failure(STEP_VERIFY,
                    "Could not download the checksum file. Check your internet connection.");
        }

        // Step 3: download (skipped when a verified copy is already present).
        if (tarFile.exists() && verifySha256(tarFile, expectedSha256)) {
            report(listener, STEP_DOWNLOAD, 100, "Alpine download already present");
        } else {
            try {
                if (tarFile.exists()) tarFile.delete();
                downloadFile(ALPINE_URL, tarFile, listener);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Download failed", e);
                tarFile.delete();
                return InstallResult.failure(STEP_DOWNLOAD,
                        "Download failed: " + plainMessage(e));
            }
            if (!tarFile.exists() || tarFile.length() < 1000000) {
                tarFile.delete();
                return InstallResult.failure(STEP_DOWNLOAD,
                        "Download failed: the file is too small (" + tarFile.length() + " bytes).");
            }
        }

        // Step 4: verify checksum before extracting anything.
        report(listener, STEP_VERIFY, 0, "Verifying checksum");
        if (!verifySha256(tarFile, expectedSha256)) {
            tarFile.delete();
            return InstallResult.failure(STEP_VERIFY,
                    "Checksum mismatch. The download was deleted; please retry.");
        }
        report(listener, STEP_VERIFY, 100, "Checksum verified");

        // Step 5: extract. A partial rootfs is deleted first.
        report(listener, STEP_EXTRACT, 0, "Extracting root filesystem");
        File rootfsDir = getRootfsDir(context);
        deleteRecursively(rootfsDir);
        rootfsDir.mkdirs();
        try {
            extractTarGz(tarFile, rootfsDir);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Extraction failed", e);
            deleteRecursively(rootfsDir);
            return InstallResult.failure(STEP_EXTRACT, "Extraction failed: " + plainMessage(e));
        }
        File sh = new File(rootfsDir, "bin/sh");
        if (!sh.exists() && !new File(rootfsDir, "usr/bin/sh").exists()
                && !new File(rootfsDir, "bin/busybox").exists()
                && !new File(rootfsDir, "usr/bin/busybox").exists()) {
            deleteRecursively(rootfsDir);
            return InstallResult.failure(STEP_EXTRACT,
                    "Extraction finished but no shell was found in the root filesystem.");
        }
        report(listener, STEP_EXTRACT, 100, "Root filesystem extracted");

        // Step 6: repositories + DNS.
        report(listener, STEP_REPOS, 0, "Setting up repositories");
        try {
            setupRootfs(context, rootfsDir);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Rootfs setup failed", e);
            return InstallResult.failure(STEP_REPOS, "Setup failed: " + plainMessage(e));
        }
        report(listener, STEP_REPOS, 100, "Repositories ready");

        // Step 7: install Bash with a one-shot PRoot run.
        report(listener, STEP_BASH, 0, "Installing Bash (apk update, apk add bash)");
        StringBuilder oneShotOutput = new StringBuilder();
        int exitCode;
        try {
            exitCode = runOneShot(context, "apk update && apk add bash", oneShotOutput);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Bash install failed", e);
            return InstallResult.failure(STEP_BASH, "Bash install failed: " + plainMessage(e));
        }
        if (exitCode != 0 || !new File(rootfsDir, "bin/bash").exists()) {
            String tail = tailOf(oneShotOutput.toString(), 500);
            return InstallResult.failure(STEP_BASH,
                    "Bash install failed (exit " + exitCode + "). " + tail);
        }
        report(listener, STEP_BASH, 100, "Bash installed");

        // Marker last: only a fully installed rootfs counts as installed.
        try {
            writeMarker(context, expectedSha256);
        } catch (Exception e) {
            return InstallResult.failure(STEP_BASH, "Could not write install marker: " + plainMessage(e));
        }
        return InstallResult.success();
    }

    private static void writeMarker(Context context, String sha256) throws IOException {
        File marker = getMarkerFile(context);
        marker.getParentFile().mkdirs();
        String content = ALPINE_VERSION + "\n" + sha256 + "\n" + System.currentTimeMillis() + "\n";
        FileOutputStream out = new FileOutputStream(marker);
        try {
            out.write(content.getBytes("UTF-8"));
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    private static void setupRootfs(Context context, File rootfs) throws IOException {
        File apkDir = new File(rootfs, "etc/apk");
        apkDir.mkdirs();
        File repos = new File(apkDir, "repositories");
        boolean reposValid = false;
        if (repos.exists()) {
            String existing = readFileUtf8(repos);
            reposValid = existing != null && existing.contains("dl-cdn.alpinelinux.org/alpine/v3.24");
        }
        if (!reposValid) {
            String content = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n"
                    + "https://dl-cdn.alpinelinux.org/alpine/v3.24/community\n";
            writeFileUtf8(repos, content);
        }

        // DNS: the rootfs cannot read the host resolver, so write the same
        // static resolv.conf the proven NobiIDE build used (it worked on
        // device). Written on every fresh extract; a later reinstall
        // rewrites it the same way.
        File resolvConf = new File(rootfs, "etc/resolv.conf");
        resolvConf.getParentFile().mkdirs();
        writeFileUtf8(resolvConf, "nameserver 8.8.8.8\nnameserver 8.8.4.4\n");

        // Termux-style startup (M2.4, see TERMUX_STYLE_SPEC.md): the
        // welcome text lives in /etc/motd (app-owned, rewritten on every
        // fresh extract) and is printed for every login shell by
        // /etc/profile.d/nobi-motd.sh — Alpine's /etc/profile sources
        // /etc/profile.d/*.sh. The launcher prints no welcome text of
        // its own, so the MOTD appears exactly once per new session.
        writeFileUtf8(new File(rootfs, "etc/motd"), MOTD_TEXT);
        File profileD = new File(rootfs, "etc/profile.d");
        profileD.mkdirs();
        writeFileUtf8(new File(profileD, "nobi-motd.sh"),
                "if [ -f /etc/motd ]; then cat /etc/motd; fi\n");

        // Default prompt, Termux-style (`~ $`): a plain PS1 in the
        // user's .bashrc. The file is user-owned — only create it when
        // absent, never overwrite an existing .bashrc.
        File bashrc = new File(rootfs, "root/.bashrc");
        if (!bashrc.exists()) {
            writeFileUtf8(bashrc, "PS1='\\w \\$ '\n");
        }

        // Login shells read ~/.bash_profile, not ~/.bashrc, so make
        // sure the profile sources .bashrc (appended only when missing,
        // never duplicated on reinstall).
        ensureBashProfileSourcesBashrc(new File(rootfs, "root/.bash_profile"));
    }

    /** Append the .bashrc-sourcing line to ~/.bash_profile only when it is not already there. */
    private static void ensureBashProfileSourcesBashrc(File bashProfile) throws IOException {
        String sourceLine = "if [ -f ~/.bashrc ]; then . ~/.bashrc; fi";
        String existing = readFileUtf8(bashProfile);
        if (existing != null && existing.contains(".bashrc")) return;
        StringBuilder content = new StringBuilder();
        if (existing != null && !existing.isEmpty()) {
            content.append(existing);
            if (!existing.endsWith("\n")) content.append("\n");
        }
        content.append(sourceLine).append("\n");
        writeFileUtf8(bashProfile, content.toString());
    }

    /** One-shot PRoot run: {@code /bin/sh -c <command>} inside the rootfs. Returns the exit code. */
    public static int runOneShot(Context context, String command, StringBuilder output) throws IOException, InterruptedException {
        String[] argv = AlpineLauncher.buildOneShotCommand(context, command);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().clear();
        Map<String, String> env = AlpineEnvironment.buildEnvironmentMap(context);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String text = readAll(process.getInputStream());
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out after 10 minutes: " + command);
        }
        if (output != null) output.append(text);
        return process.exitValue();
    }

    private static String fetchChecksum() throws IOException {
        URL url = new URL(ALPINE_URL + ".sha256");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NobiAlpine");
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("Checksum file returned HTTP " + code);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        try {
            String line = reader.readLine();
            if (line == null || line.trim().isEmpty()) throw new IOException("Empty checksum file");
            String token = line.trim().split("\\s+")[0].toLowerCase(Locale.US);
            if (token.length() != 64) throw new IOException("Unexpected checksum format");
            return token;
        } finally {
            reader.close();
        }
    }

    private static boolean verifySha256(File file, String expectedHex) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream in = new BufferedInputStream(new FileInputStream(file));
            try {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            } finally {
                in.close();
            }
            return toHex(digest.digest()).equals(expectedHex.toLowerCase(Locale.US));
        } catch (Exception e) {
            Log.e(LOG_TAG, "Checksum verification error", e);
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static void downloadFile(String urlString, File dest, final ProgressListener listener) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NobiAlpine");
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("Download returned HTTP " + code);
        }
        long total = connection.getContentLengthLong();
        InputStream input = connection.getInputStream();
        try {
            OutputStream output = new FileOutputStream(dest);
            try {
                byte[] buffer = new byte[32768];
                long downloaded = 0;
                int n;
                int lastPercent = -1;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                    downloaded += n;
                    if (total > 0) {
                        int percent = (int) (downloaded * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            String detail = String.format(Locale.US, "Downloaded %.1f / %.1f MB",
                                    downloaded / 1048576.0, total / 1048576.0);
                            report(listener, STEP_DOWNLOAD, percent, detail);
                        }
                    } else {
                        report(listener, STEP_DOWNLOAD, -1, String.format(Locale.US,
                                "Downloaded %.1f MB", downloaded / 1048576.0));
                    }
                }
                output.flush();
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
    }

    private static void extractTarGz(File tarGz, File destDir) throws IOException, InterruptedException {
        // Try the system tar first (toybox tar on Android handles this well).
        try {
            ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tarGz.getAbsolutePath(), "-C", destDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = readAll(process.getInputStream());
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (finished && process.exitValue() == 0) {
                Log.d(LOG_TAG, "system tar extraction succeeded");
                return;
            }
            Log.w(LOG_TAG, "system tar failed (finished=" + finished + ", exit="
                    + (finished ? process.exitValue() : -1) + "): " + output);
            if (!finished) process.destroyForcibly();
        } catch (Exception e) {
            Log.w(LOG_TAG, "system tar not available: " + e.getMessage());
        }
        // Fallback: pure-Java tar.gz extraction (adapted from the proven
        // NobiIDE extractor, plus hardlink and ustar-prefix handling).
        extractTarGzJava(tarGz, destDir);
    }

    private static void extractTarGzJava(File tarGz, File destDir) throws IOException {
        String destCanonical = destDir.getCanonicalPath();
        GZIPInputStream gz = new GZIPInputStream(new FileInputStream(tarGz));
        try {
            byte[] header = new byte[512];
            String pendingLongName = null;
            while (true) {
                int read = 0;
                while (read < 512) {
                    int n = gz.read(header, read, 512 - read);
                    if (n == -1) break;
                    read += n;
                }
                if (read < 512) break;
                boolean allZero = true;
                for (byte b : header) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) break;

                String name = fieldString(header, 0, 100);
                String prefix = fieldString(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                if (pendingLongName != null) {
                    name = pendingLongName;
                    pendingLongName = null;
                }
                long size = parseOctal(header, 124, 12);
                int mode = (int) parseOctal(header, 100, 8);
                char typeFlag = (char) header[156];
                String linkName = fieldString(header, 157, 100);

                if (typeFlag == 'L') {
                    // GNU long name: data is the real name for the next entry.
                    pendingLongName = readDataAsString(gz, size);
                    skipPadding(gz, size);
                    continue;
                }
                if (typeFlag == 'K') {
                    readDataAsString(gz, size);
                    skipPadding(gz, size);
                    continue;
                }
                if (typeFlag == 'x' || typeFlag == 'g') {
                    // PAX extended header: skip its data.
                    skipBytes(gz, size);
                    skipPadding(gz, size);
                    continue;
                }

                File outFile = new File(destDir, name);
                String outCanonical = outFile.getCanonicalPath();
                boolean inside = outCanonical.equals(destCanonical)
                        || outCanonical.startsWith(destCanonical + File.separator);
                if (!inside) {
                    skipBytes(gz, size);
                    skipPadding(gz, size);
                    continue;
                }

                if (typeFlag == '5') {
                    outFile.mkdirs();
                    skipPadding(gz, size);
                } else if (typeFlag == '2') {
                    // Symlink.
                    createSymlink(outFile, linkName);
                    skipPadding(gz, size);
                } else if (typeFlag == '1') {
                    // Hard link to another entry inside the archive.
                    createHardLink(destDir, destCanonical, outFile, linkName);
                    skipPadding(gz, size);
                } else {
                    // Regular file ('0', '\0', or anything else with data).
                    if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
                    writeEntryData(gz, outFile, size);
                    skipPadding(gz, size);
                    if ((mode & 0111) != 0
                            || name.startsWith("bin/") || name.startsWith("sbin/")
                            || name.startsWith("usr/bin/") || name.startsWith("usr/sbin/")) {
                        outFile.setExecutable(true);
                    }
                }
            }
        } finally {
            gz.close();
        }
        Log.d(LOG_TAG, "Java tar.gz extraction complete");
    }

    private static void createSymlink(File outFile, String linkTarget) {
        try {
            if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
            if (outFile.exists() || isSymlink(outFile)) outFile.delete();
            try {
                java.nio.file.Files.createSymbolicLink(outFile.toPath(),
                        java.nio.file.Paths.get(linkTarget));
                return;
            } catch (Exception e) {
                Log.w(LOG_TAG, "createSymbolicLink failed, trying ln: " + e.getMessage());
            }
            runQuietly(new String[]{"ln", "-sf", linkTarget, outFile.getAbsolutePath()});
        } catch (Exception e) {
            Log.w(LOG_TAG, "Symlink failed: " + outFile + " -> " + linkTarget + ": " + e.getMessage());
        }
    }

    private static void createHardLink(File destDir, String destCanonical, File outFile, String linkName) {
        try {
            File target = new File(destDir, linkName);
            String targetCanonical = target.getCanonicalPath();
            if (!targetCanonical.startsWith(destCanonical + File.separator)) return;
            if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
            if (outFile.exists()) outFile.delete();
            try {
                java.nio.file.Files.createLink(outFile.toPath(), target.toPath());
                return;
            } catch (Exception e) {
                Log.w(LOG_TAG, "createLink failed, trying ln: " + e.getMessage());
            }
            runQuietly(new String[]{"ln", target.getAbsolutePath(), outFile.getAbsolutePath()});
        } catch (Exception e) {
            Log.w(LOG_TAG, "Hard link failed: " + outFile + " -> " + linkName + ": " + e.getMessage());
        }
    }

    private static boolean isSymlink(File file) {
        try {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static void runQuietly(String[] command) {
        try {
            Process process = new ProcessBuilder(command).start();
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static String fieldString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        try {
            return new String(header, offset, end - offset, "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        String s = fieldString(header, offset, length).trim();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void writeEntryData(InputStream in, File outFile, long size) throws IOException {
        FileOutputStream out = new FileOutputStream(outFile);
        try {
            byte[] buffer = new byte[8192];
            long remaining = size;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int n = in.read(buffer, 0, toRead);
                if (n == -1) break;
                out.write(buffer, 0, n);
                remaining -= n;
            }
            out.flush();
        } finally {
            out.close();
        }
    }

    private static String readDataAsString(InputStream in, long size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n == -1) break;
            out.write(buffer, 0, n);
            remaining -= n;
        }
        try {
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void skipBytes(InputStream in, long size) throws IOException {
        long remaining = size;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n == -1) break;
            remaining -= n;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (512 - (size % 512)) % 512;
        skipBytes(in, padding);
    }

    private static String readAll(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[4096];
        int n;
        while ((n = reader.read(buffer)) != -1) sb.append(buffer, 0, n);
        return sb.toString();
    }

    private static String readFileUtf8(File file) {
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFileUtf8(File file, String content) throws IOException {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (isSymlink(file) || file.isFile()) {
            file.delete();
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static void report(ProgressListener listener, String stepId, int percent, String detail) {
        if (listener != null) listener.onStep(stepId, percent, detail);
    }

    private static String plainMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) return e.toString();
        return message;
    }

    private static String tailOf(String text, int maxChars) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) return trimmed;
        return trimmed.substring(trimmed.length() - maxChars);
    }
}
