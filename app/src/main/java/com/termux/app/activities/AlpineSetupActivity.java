package com.termux.app.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;

/** M1 placeholder Alpine setup screen. The real Alpine installer arrives in M2. */
public final class AlpineSetupActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alpine_setup);

        Button openTerminalButton = findViewById(R.id.alpine_setup_open_terminal);
        openTerminalButton.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(TermuxInstaller.NOBI_ALPINE_PREFS, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(TermuxInstaller.NOBI_ALPINE_SETUP_SEEN, true).apply();

            Intent intent = new Intent(this, TermuxActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}
