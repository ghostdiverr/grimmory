package org.booklore.service.metadata.parser.babelio;

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
    }
}
