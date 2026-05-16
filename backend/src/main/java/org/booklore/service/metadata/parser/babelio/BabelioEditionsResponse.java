package org.booklore.service.metadata.parser.babelio;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class BabelioEditionsResponse {
    private int success;
    private List<Edition> editions;

    @Data
    public static class Edition {
        private String id;
        private String ean13;
        private String isbn;
        private String couverture;
        @JsonProperty("nb_pages")
        private String nbPages;
        private String nom;
        @JsonProperty("dt_publication")
        private String dtPublication;
    }
}
