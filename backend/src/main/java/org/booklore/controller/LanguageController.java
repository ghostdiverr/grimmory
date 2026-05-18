package org.booklore.controller;

import com.neovisionaries.i18n.LanguageCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/languages")
@Tag(name = "Languages", description = "ISO 639-1 language list")
public class LanguageController {

    public record LanguageDto(String code, String name) {}

    @Operation(summary = "Get all supported languages", description = "Returns all ISO 639-1 language codes with names localized to the requested language.")
    @GetMapping
    public List<LanguageDto> getLanguages(@RequestParam(defaultValue = "en") String lang) {
        Locale displayLocale = Locale.forLanguageTag(lang);
        return Arrays.stream(LanguageCode.values())
                .filter(c -> !c.name().equals("undefined"))
                .map(c -> {
                    String name = new Locale(c.name()).getDisplayLanguage(displayLocale);
                    return new LanguageDto(c.name(), name);
                })
                .sorted(Comparator.comparing(LanguageDto::name))
                .toList();
    }
}
