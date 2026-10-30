package com.termux.app.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * The filesystem binds PRoot applies for every Alpine process.
 * Single source for the bind list used by {@link AlpineLauncher}.
 * Storage binds expose the user's shared storage only; no private
 * Android directories are bound into Alpine.
 */
public final class AlpineMountManager {

    private AlpineMountManager() {
    }

    /** Bind arguments (pairs of "-b", "host:guest" or "-b", "path") for PRoot. */
    public static List<String> buildBindArguments() {
        List<String> args = new ArrayList<>();
        args.add("-b");
        args.add("/sdcard:/sdcard");
        args.add("-b");
        args.add("/storage:/storage");
        return args;
    }
}
