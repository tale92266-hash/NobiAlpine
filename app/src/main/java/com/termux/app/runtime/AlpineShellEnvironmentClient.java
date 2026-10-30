package com.termux.app.runtime;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.shell.ShellEnvironmentClient;
import com.termux.shared.termux.TermuxConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shell environment for Alpine sessions. Used only for default sessions
 * when Alpine is installed; plugin execution commands and failsafe
 * sessions keep the Termux client.
 *
 * <p>{@link #getDefaultWorkingDirectoryPath()} is a real host path: the JNI
 * subprocess does {@code chdir(cwd)} on the host before exec'ing PRoot,
 * and PRoot's {@code -w /root} sets the guest working directory. The
 * default bin path is never consulted for Alpine sessions (the PRoot
 * binary is set as the executable directly).</p>
 */
public class AlpineShellEnvironmentClient implements ShellEnvironmentClient {

    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_FILES_DIR_PATH + "/alpine/home";
    }

    @NonNull
    @Override
    public String getDefaultBinPath() {
        return TermuxConstants.TERMUX_FILES_DIR_PATH + "/alpine";
    }

    @NonNull
    @Override
    public String[] buildEnvironment(Context currentPackageContext, boolean isFailSafe, String workingDirectory) {
        return AlpineEnvironment.buildEnvironment(currentPackageContext, isFailSafe, workingDirectory);
    }

    @NonNull
    @Override
    public String[] setupProcessArgs(@NonNull String fileToExecute, String[] arguments) {
        // The executable is the PRoot ELF binary: execute it directly with
        // its arguments, no interpreter rewriting (unlike Termux's shell
        // script handling). setupProcessArgs must never mangle argv here.
        List<String> result = new ArrayList<>();
        result.add(fileToExecute);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }
}
