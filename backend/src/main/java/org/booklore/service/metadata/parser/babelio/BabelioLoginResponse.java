package org.booklore.service.metadata.parser.babelio;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BabelioLoginResponse {
    private int success;
    private int code;
    private String reason;
    private String token;
    @JsonProperty("user_id")
    private String userId;
}
