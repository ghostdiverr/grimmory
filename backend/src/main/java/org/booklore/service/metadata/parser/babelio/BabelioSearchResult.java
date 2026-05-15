package org.booklore.service.metadata.parser.babelio;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class BabelioSearchResult {
    private int success;
    private String reason;
    private List<Result> results;

    @Data
    public static class Result {
        @JsonProperty("id_oeuvre")
        private String idOeuvre;
        private String titre;
        private String type;
        private String url;
    }
}
