package org.booklore.util;

import com.neovisionaries.i18n.LanguageAlpha3Code;
import com.neovisionaries.i18n.LanguageCode;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class LanguageNormalizer {

    private LanguageNormalizer() {}

    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();

        // Handle BCP 47 / IETF tags ("fr-FR", "fr_FR") — take the primary subtag
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
        // ISO 639-1 (case-insensitive): "fr", "FR"
        LanguageCode byCode = LanguageCode.getByCode(input, false);
        if (byCode != null) {
            return byCode.name();
        }

        // ISO 639-2 alpha-3 (case-insensitive): "fre", "fra", "eng"
        LanguageAlpha3Code byAlpha3 = LanguageAlpha3Code.getByCode(input, false);
        if (byAlpha3 != null) {
            LanguageCode alpha2 = byAlpha3.getAlpha2();
            if (alpha2 != null) {
                return alpha2.name();
            }
        }

        // English name match (case-insensitive): "French", "German", etc.
        List<LanguageCode> byName = LanguageCode.findByName("(?i)" + Pattern.quote(input));
        if (!byName.isEmpty()) {
            return byName.get(0).name();
        }

        // Native / localized name via JDK CLDR: "français"→fr, "espagnol"→es, "anglais"→en, etc.
        return resolveByLocaleDisplayName(input);
    }

    private static final Locale[] DISPLAY_LOCALES = {
        Locale.FRENCH, Locale.GERMAN, Locale.ITALIAN,
        new Locale("es"), new Locale("pt"), new Locale("nl"),
        new Locale("ru"), new Locale("pl"), new Locale("ja")
    };

    private static String resolveByLocaleDisplayName(String input) {
        String inputLower = input.toLowerCase(Locale.ROOT).trim();
        for (LanguageCode code : LanguageCode.values()) {
            if (code == LanguageCode.undefined) continue;
            Locale locale = new Locale(code.name());
            for (Locale displayLocale : DISPLAY_LOCALES) {
                String displayName = locale.getDisplayLanguage(displayLocale).toLowerCase(Locale.ROOT);
                if (displayName.equals(inputLower) && displayName.length() > 2) {
                    return code.name();
                }
            }
        }
        return null;
    }
}
