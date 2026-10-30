package com.termux.app.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;
import com.termux.app.runtime.AlpineInstaller;

import java.util.HashMap;
import java.util.Map;

/**
 * Alpine setup screen (M2). Runs the real Alpine installer on a background
 * thread and shows a live checklist. The install starts only when the user
 * taps the button. After a successful install the button becomes
 * "Open Terminal". A failed step shows a plain-English error and a single
 * Retry button that re-runs the install (completed steps are skipped when
 * their output is already present and verified).
 */
public final class AlpineSetupActivity extends Activity {

    private static final int STATE_PENDING = 0;
    private static final int STATE_ACTIVE = 1;
    private static final int STATE_DONE = 2;
    private static final int STATE_FAILED = 3;

    private final Map<String, TextView> stepViews = new HashMap<>();
    private final Map<String, String> stepLabels = new HashMap<>();

    private TextView progressView;
    private TextView errorView;
    private Button installButton;
    private Button retryButton;

    private volatile boolean installing;
    private volatile boolean installed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alpine_setup);

        stepLabels.put(AlpineInstaller.STEP_CHECK, "Checking device (arm64)");
        stepLabels.put(AlpineInstaller.STEP_PROOT, "Checking PRoot");
        stepLabels.put(AlpineInstaller.STEP_VERIFY, "Verifying bundled Alpine");
        stepLabels.put(AlpineInstaller.STEP_EXTRACT, "Extracting root filesystem");
        stepLabels.put(AlpineInstaller.STEP_REPOS, "Setting up repositories");
        stepLabels.put(AlpineInstaller.STEP_BASH, "Installing Bash");

        stepViews.put(AlpineInstaller.STEP_CHECK, (TextView) findViewById(R.id.alpine_setup_step_check));
        stepViews.put(AlpineInstaller.STEP_PROOT, (TextView) findViewById(R.id.alpine_setup_step_proot));
        stepViews.put(AlpineInstaller.STEP_VERIFY, (TextView) findViewById(R.id.alpine_setup_step_verify));
        stepViews.put(AlpineInstaller.STEP_EXTRACT, (TextView) findViewById(R.id.alpine_setup_step_extract));
        stepViews.put(AlpineInstaller.STEP_REPOS, (TextView) findViewById(R.id.alpine_setup_step_repos));
        stepViews.put(AlpineInstaller.STEP_BASH, (TextView) findViewById(R.id.alpine_setup_step_bash));

        progressView = (TextView) findViewById(R.id.alpine_setup_progress);
        errorView = (TextView) findViewById(R.id.alpine_setup_error);
        installButton = (Button) findViewById(R.id.alpine_setup_install);
        retryButton = (Button) findViewById(R.id.alpine_setup_retry);

        resetSteps();

        if (AlpineInstaller.isInstalled(this)) {
            markAllDone();
            showOpenTerminal();
            return;
        }

        // A rootfs directory without a valid marker means a previous
        // install failed or was corrupted: this run reinstalls it.
        if (AlpineInstaller.hasPartialRootfs(this)) {
            installButton.setText("Reinstall Alpine");
        } else {
            installButton.setText("Install Alpine");
        }

        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (installed) {
                    openTerminal();
                } else if (!installing) {
                    startInstall();
                }
            }
        });

        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!installing) startInstall();
            }
        });
    }

    private void startInstall() {
        installing = true;
        installed = false;
        resetSteps();
        errorView.setVisibility(View.GONE);
        errorView.setText("");
        progressView.setText("");
        retryButton.setVisibility(View.GONE);
        installButton.setEnabled(false);
        installButton.setText("Installing...");

        final Context appContext = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AlpineInstaller.InstallResult result = AlpineInstaller.install(
                        appContext, new AlpineInstaller.ProgressListener() {
                            @Override
                            public void onStep(final String stepId, final int percent, final String detail) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isFinishing()) return;
                                        setStepState(stepId, STATE_ACTIVE);
                                        if (percent == 100) setStepState(stepId, STATE_DONE);
                                        if (detail != null && !detail.isEmpty()) {
                                            progressView.setText(detail);
                                        }
                                    }
                                });
                            }
                        });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        installing = false;
                        if (isFinishing()) return;
                        if (result.ok) {
                            installed = true;
                            markAllDone();
                            progressView.setText("Alpine Linux 3.24.3 is ready.");
                            showOpenTerminal();
                        } else {
                            setStepState(result.failedStep, STATE_FAILED);
                            String label = stepLabels.get(result.failedStep);
                            String message = "Install failed"
                                    + (label == null ? "" : " at: " + label)
                                    + ". " + result.error;
                            if (message.length() > 300) message = message.substring(0, 300);
                            errorView.setText(message);
                            errorView.setVisibility(View.VISIBLE);
                            retryButton.setVisibility(View.VISIBLE);
                            installButton.setEnabled(true);
                            installButton.setText(AlpineInstaller.hasPartialRootfs(AlpineSetupActivity.this)
                                    ? "Reinstall Alpine" : "Install Alpine");
                        }
                    }
                });
            }
        }).start();
    }

    private void showOpenTerminal() {
        installButton.setEnabled(true);
        installButton.setText("Open Terminal");
        retryButton.setVisibility(View.GONE);
        // Keep the "setup seen" flag for compatibility with older gates.
        SharedPreferences prefs = getSharedPreferences(TermuxInstaller.NOBI_ALPINE_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(TermuxInstaller.NOBI_ALPINE_SETUP_SEEN, true).apply();
    }

    private void openTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void resetSteps() {
        for (Map.Entry<String, TextView> entry : stepViews.entrySet()) {
            setStepState(entry.getKey(), STATE_PENDING);
        }
    }

    private void markAllDone() {
        for (String stepId : stepViews.keySet()) {
            setStepState(stepId, STATE_DONE);
        }
    }

    private void setStepState(String stepId, int state) {
        TextView view = stepViews.get(stepId);
        if (view == null) return;
        String label = stepLabels.get(stepId);
        if (label == null) label = stepId;
        switch (state) {
            case STATE_DONE:
                view.setText("\u2713 " + label);
                view.setAlpha(1.0f);
                break;
            case STATE_FAILED:
                view.setText("\u2717 " + label);
                view.setAlpha(1.0f);
                break;
            case STATE_ACTIVE:
                view.setText("\u25CF " + label);
                view.setAlpha(1.0f);
                break;
            default:
                view.setText("\u25CB " + label);
                view.setAlpha(0.5f);
                break;
        }
    }
}
