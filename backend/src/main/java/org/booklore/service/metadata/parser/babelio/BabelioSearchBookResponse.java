package org.booklore.service.metadata.parser.babelio;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class BabelioSearchBookResponse {
    private int success;
    private List<Book> books;

    @Data
    public static class Book {
        @JsonProperty("book_id")
        private String bookId;
        @JsonProperty("id_edition")
        private String idEdition;
        @JsonProperty("book_title")
        private String bookTitle;
        @JsonProperty("author_first_name")
        private String authorFirstName;
        @JsonProperty("author_last_name")
        private String authorLastName;
        @JsonProperty("bookcover_url")
        private String bookcoverUrl;
    }
}
