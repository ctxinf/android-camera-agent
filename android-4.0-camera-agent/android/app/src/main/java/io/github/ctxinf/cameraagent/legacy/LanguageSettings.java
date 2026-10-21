package io.github.ctxinf.cameraagent.legacy;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.preference.PreferenceManager;
import java.util.Locale;

/** Locale override without adding dependencies to the API 14 app. */
public final class LanguageSettings {
    public static final String KEY = "app_language";
    private LanguageSettings() { }

    public static String selected(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "");
    }

    public static Context wrap(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        String tag = selected(context);
        Locale locale;
        if (tag.length() == 0) {
            Configuration system = Resources.getSystem().getConfiguration();
            locale = Build.VERSION.SDK_INT >= 24 ? system.getLocales().get(0) : system.locale;
        } else {
            String[] parts = tag.split("-", 2);
            locale = parts.length == 1 ? new Locale(parts[0]) : new Locale(parts[0], parts[1]);
        }
        if (Build.VERSION.SDK_INT >= 17) {
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            return context.createConfigurationContext(config);
        }
        config.locale = locale;
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
        return context;
    }
}
