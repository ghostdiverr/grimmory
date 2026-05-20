package org.booklore.util;

import com.neovisionaries.i18n.LanguageAlpha3Code;
import com.neovisionaries.i18n.LanguageCode;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class LanguageNormalizer {

    private LanguageNormalizer() {}

    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();

        // BCP 47 / IETF tags ("fr-FR", "fr_FR") — take primary subtag first
        String primary = trimmed;
        if (trimmed.contains("-") || trimmed.contains("_")) {
            primary = trimmed.split("[-_]")[0];
        }

        String result = resolveCode(primary);
        if (result == null && !primary.equals(trimmed)) {
            result = resolveCode(trimmed);
        }

        return result != null ? result : trimmed.toLowerCase().strip();
    }

    private static String resolveCode(String input) {
        LanguageCode byCode = LanguageCode.getByCode(input, false);
        if (byCode != null) return byCode.name();

        LanguageAlpha3Code byAlpha3 = LanguageAlpha3Code.getByCode(input, false);
        if (byAlpha3 != null) {
            LanguageCode alpha2 = byAlpha3.getAlpha2();
            if (alpha2 != null) return alpha2.name();
        }

        List<LanguageCode> byName = LanguageCode.findByName("(?i)" + Pattern.quote(input));
        if (!byName.isEmpty()) return byName.get(0).name();

        return LOCALIZED_NAME_MAP.get(input.toLowerCase(Locale.ROOT).trim());
    }

    /**
     * Static map built once from JDK CLDR data.
     * Covers localized language names ("français"→fr, "espagnol"→es, "polski"→pl, etc.)
     * across the languages most likely to appear as metadata values.
     */
    private static final Map<String, String> LOCALIZED_NAME_MAP = buildLocalizedNameMap();

    private static Map<String, String> buildLocalizedNameMap() {
        Locale[] displayLocales = {
            Locale.FRENCH, Locale.GERMAN, Locale.ITALIAN,
            new Locale("es"), new Locale("pt"), new Locale("nl"),
            new Locale("ru"), new Locale("pl"), new Locale("ja")
        };
        Map<String, String> map = new HashMap<>();
        for (LanguageCode code : LanguageCode.values()) {
            if (code == LanguageCode.undefined) continue;
            Locale locale = new Locale(code.name());
            for (Locale displayLocale : displayLocales) {
                String displayName = locale.getDisplayLanguage(displayLocale).toLowerCase(Locale.ROOT);
                // skip trivial cases where the display name is the ISO code itself
                if (displayName.length() > 2) {
                    map.putIfAbsent(displayName, code.name());
                }
            }
        }
        return Map.copyOf(map);
    }
}
