package org.booklore.service.metadata.parser.babelio;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class BabelioBookDetails {
    private int success;
    private String reason;
    @JsonProperty("book_all")
    private BookAll bookAll;

    @Data
    public static class BookAll {
        @JsonProperty("book_infoglobal")
        private BookInfoGlobal bookInfoGlobal;
        @JsonProperty("tags_on_book")
        private TagsOnBook tagsOnBook;
        private List<Serie> serie;
    }

    @Data
    public static class BookInfoGlobal {
        @JsonProperty("book_info")
        private BookInfo bookInfo;
    }

    @Data
    public static class BookInfo {
        @JsonProperty("book_title")
        private String bookTitle;
        @JsonProperty("ISBN")
        private String isbn10;
        private String ean13;
        @JsonProperty("author_list")
        private List<Author> authorList;
        private String summary;
        @JsonProperty("publisher_name")
        private String publisherName;
        @JsonProperty("publishing_date")
        private String publishingDate;
        @JsonProperty("nb_pages")
        private String nbPages;
        @JsonProperty("average_rating")
        private String averageRating;
        @JsonProperty("bookcoverbig_url")
        private String coverUrl;
    }

    @Data
    public static class Serie {
        private String id;
        private String nom;
        private String tome;
    }

    @Data
    public static class Author {
        @JsonProperty("id_auteur")
        private String idAuteur;
        @JsonProperty("first_name")
        private String firstName;
        @JsonProperty("last_name")
        private String lastName;
    }

    @Data
    public static class TagsOnBook {
        private List<Tag> tags;
    }

    @Data
    public static class Tag {
        private String libelle;
        @JsonProperty("nb_occurrences")
        private String nbOccurrences;
    }
}
