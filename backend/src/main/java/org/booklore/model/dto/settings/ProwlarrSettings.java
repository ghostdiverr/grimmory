package org.booklore.model.dto.settings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor(onConstructor_ = @JsonCreator)
public class ProwlarrSettings {
    @Builder.Default @JsonSetter(nulls = Nulls.SKIP)
    private boolean enabled = false;
    @JsonSetter(nulls = Nulls.SKIP)
    private String baseUrl;
    @JsonSetter(nulls = Nulls.SKIP)
    private String apiKey;
    @Builder.Default @JsonSetter(nulls = Nulls.SKIP)
    private List<Integer> bookCategories = List.of();
    @Builder.Default @JsonSetter(nulls = Nulls.SKIP)
    private List<Integer> audiobookCategories = List.of();
    @JsonSetter(nulls = Nulls.SKIP)
    private String completedDownloadsFolder;
    @JsonSetter(nulls = Nulls.SKIP)
    private String bookSubfolder;
    @JsonSetter(nulls = Nulls.SKIP)
    private String audiobookSubfolder;
}
