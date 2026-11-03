package com.pubburi.pub.controller.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SelfPasswordRequest(
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank @Size(max = 100)
        @JsonDeserialize(using = StrictStringDeserializer.class) String currentPassword,
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, description = "Literal 4–100 UTF-16 code units, at most 72 UTF-8 bytes")
        @Utf8ByteLength(max = 72, message = "must contain at most 72 UTF-8 bytes") @NotBlank @Size(min = 4, max = 100)
        @JsonDeserialize(using = StrictStringDeserializer.class) String newPassword) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported password field");
    }
}
