package com.laglab.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import java.security.MessageDigest;
import java.util.UUID;

/** Optional app password. Only a salted hash is stored on the phone. */
public class Lock {
    private static AlertDialog open;

    private static SharedPreferences prefs(Activity a) {
        return a.getSharedPreferences("lock", Activity.MODE_PRIVATE);
    }

    private static String hash(String salt, String password) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + password).getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (byte b : d) s.append(String.format("%02x", b));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EditText field(Activity a, String hint) {
        EditText input = new EditText(a);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    /** Hides the screen until the right password is typed. Does nothing if none is set. */
    public static void show(Activity a) {
        String saved = prefs(a).getString("hash", null);
        if (open != null) {
            try { open.dismiss(); } catch (Exception ignored) { }
            open = null;
        }
        View screen = a.findViewById(android.R.id.content);
        if (saved == null) { screen.setVisibility(View.VISIBLE); return; }
        screen.setVisibility(View.INVISIBLE);
        String salt = prefs(a).getString("salt", "");
        EditText input = field(a, "পাসওয়ার্ড");
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("LagLab লক করা")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("খুলুন", null)
                .setNegativeButton("বের হন", (x, w) -> a.finish())
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (hash(salt, input.getText().toString()).equals(saved)) {
                screen.setVisibility(View.VISIBLE);
                d.dismiss();
            } else input.setError("ভুল পাসওয়ার্ড");
        }));
        open = d;
        d.show();
    }

    /** Sets, changes or removes the password. */
    public static void change(Activity a) {
        EditText input = field(a, "নতুন পাসওয়ার্ড (খালি রাখলে লক বন্ধ)");
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("পাসওয়ার্ড")
                .setView(input)
                .setPositiveButton("সেভ", null)
                .setNegativeButton("বাতিল", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String password = input.getText().toString();
            if (!password.isEmpty() && password.length() < 4) {
                input.setError("কমপক্ষে ৪ অক্ষর দিন");
                return;
            }
            if (password.isEmpty()) prefs(a).edit().clear().apply();
            else {
                String salt = UUID.randomUUID().toString();
                prefs(a).edit().putString("salt", salt).putString("hash", hash(salt, password)).apply();
            }
            Toast.makeText(a, password.isEmpty() ? "লক বন্ধ" : "পাসওয়ার্ড সেভ হয়েছে", Toast.LENGTH_SHORT).show();
            d.dismiss();
        }));
        d.show();
    }
}
