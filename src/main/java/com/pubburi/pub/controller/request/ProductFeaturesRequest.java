package com.pubburi.pub.controller.request;

import java.net.URI;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.pubburi.pub.model.dto.ProductFeatures;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductFeaturesRequest(
		@Schema(types = {"integer", "null"}, nullable = true, description = "Strict JSON integer or null; strings/fractions rejected")
		@Min(1) @Max(1_000_000) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer volumeMl,
		@Schema(types = {"string", "null"}, nullable = true) @Size(max = 2000)
		@JsonDeserialize(using = StrictStringDeserializer.class) String description,
		@Schema(types = {"array", "null"}, nullable = true, description = "null=unknown; []=reviewed; non-null requires sourceName")
		@Size(max = 20) @JsonDeserialize(contentUsing = StrictStringDeserializer.class)
		List<@NotBlank @Size(max = 40) String> tasteTags,
		@Schema(types = {"array", "null"}, nullable = true, description = "null=unknown; []=reviewed; non-null requires sourceName")
		@Size(max = 20) @JsonDeserialize(contentUsing = StrictStringDeserializer.class)
		List<@NotBlank @Size(max = 40) String> aromaTags,
		@Schema(types = {"string", "null"}, nullable = true, description = "Required for any known value or non-null tag array; not rights approval")
		@Size(max = 200) @JsonDeserialize(using = StrictStringDeserializer.class) String sourceName,
		@Schema(types = {"string", "null"}, nullable = true, description = "HTTP(S) URL without userinfo; stored only, never fetched")
		@Size(max = 1000) @JsonDeserialize(using = StrictStringDeserializer.class) String sourceUrl,
		@Schema(types = {"string", "null"}, nullable = true) @Size(max = 1000)
		@JsonDeserialize(using = StrictStringDeserializer.class) String sourceNote) {

	@JsonIgnore
	@Schema(hidden = true)
	@AssertTrue(message = "Known attributes require sourceName; a source record does not grant usage rights")
	public boolean isSourcePresentForKnownFeatures() {
		return (volumeMl == null && blank(description) && tasteTags == null && aromaTags == null)
				|| !blank(sourceName);
	}

	@JsonIgnore
	@Schema(hidden = true)
	@AssertTrue(message = "sourceUrl must be an absolute HTTP(S) URL without user information")
	public boolean isSourceUrlValid() {
		if (blank(sourceUrl)) {
			return true;
		}
		try {
			URI uri = URI.create(sourceUrl.strip());
			return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
					&& uri.getHost() != null && uri.getUserInfo() == null;
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	public ProductFeatures toFeatures() {
		ProductFeatures result = new ProductFeatures();
		result.setVolumeMl(volumeMl);
		result.setDescription(description);
		result.setTasteTags(tasteTags);
		result.setAromaTags(aromaTags);
		result.setSourceName(sourceName);
		result.setSourceUrl(sourceUrl);
		result.setSourceNote(sourceNote);
		return result;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
