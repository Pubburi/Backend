package com.pubburi.pub.controller.request;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.pubburi.pub.model.dto.UserPreferences;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserPreferencesRequest(
		@Schema(allowableValues = {"NOT_STARTED", "SKIPPED", "COMPLETED"})
		@NotBlank @Pattern(regexp = "NOT_STARTED|SKIPPED|COMPLETED")
		@JsonDeserialize(using = StrictStringDeserializer.class) String surveyStatus,
		@Valid PreferenceSoftInput softPreferences,
		@Valid PreferenceHardInput hardConstraints) {

	@JsonIgnore @Schema(hidden = true)
	@AssertTrue(message = "Skipped or not-started surveys must contain only unknown fields")
	public boolean isStatusConsistent() {
		return "COMPLETED".equals(surveyStatus)
				|| (softPreferences == null || !softPreferences.hasValues())
						&& (hardConstraints == null || !hardConstraints.hasValues());
	}

	public UserPreferences toPreferences() {
		UserPreferences result = new UserPreferences();
		result.setSurveyStatus(surveyStatus);
		if (softPreferences != null) {
			result.setCategories(softPreferences.categories());
			result.setTasteTags(softPreferences.tasteTags());
			result.setAromaTags(softPreferences.aromaTags());
			result.setTargetAbvPercent(softPreferences.targetAbvPercent());
		}
		if (hardConstraints != null) {
			result.setMinPriceKrw(hardConstraints.minPriceKrw());
			result.setMaxPriceKrw(hardConstraints.maxPriceKrw());
			result.setMinAbvPercent(hardConstraints.minAbvPercent());
			result.setMaxAbvPercent(hardConstraints.maxAbvPercent());
		}
		return result;
	}

	@Schema(types = {"object", "null"}, description = "Explicit soft targets, never automatically promoted to hard filters")
	public record PreferenceSoftInput(
			@Schema(types = {"array", "null"}, description = "Existing eight category labels; null=unknown, []=explicitly no preference")
			@Size(max = 8) @JsonDeserialize(contentUsing = StrictStringDeserializer.class)
			List<@NotBlank @Size(max = 20) String> categories,
			@Schema(types = {"array", "null"}) @Size(max = 20)
			@JsonDeserialize(contentUsing = StrictStringDeserializer.class) List<@NotBlank @Size(max = 40) String> tasteTags,
			@Schema(types = {"array", "null"}) @Size(max = 20)
			@JsonDeserialize(contentUsing = StrictStringDeserializer.class) List<@NotBlank @Size(max = 40) String> aromaTags,
			@Schema(types = {"number", "null"}, description = "Soft target, not a permitted ABV bound")
			@DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
			@JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal targetAbvPercent) {
		public boolean hasValues() {
			return categories != null || tasteTags != null || aromaTags != null || targetAbvPercent != null;
		}
	}

	@Schema(types = {"object", "null"}, description = "Explicit inclusive hard ranges; zero is a real bound, null is unset")
	public record PreferenceHardInput(
			@Schema(types = {"integer", "null"}) @Min(0)
			@JsonDeserialize(using = StrictIntegerDeserializer.class) Integer minPriceKrw,
			@Schema(types = {"integer", "null"}) @Min(0)
			@JsonDeserialize(using = StrictIntegerDeserializer.class) Integer maxPriceKrw,
			@Schema(types = {"number", "null"}) @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
			@JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal minAbvPercent,
			@Schema(types = {"number", "null"}) @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
			@JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal maxAbvPercent) {
		@JsonIgnore @Schema(hidden = true)
		@AssertTrue(message = "Hard lower bounds must not exceed upper bounds")
		public boolean isRangeOrdered() {
			return (minPriceKrw == null || maxPriceKrw == null || minPriceKrw <= maxPriceKrw)
					&& (minAbvPercent == null || maxAbvPercent == null || minAbvPercent.compareTo(maxAbvPercent) <= 0);
		}
		public boolean hasValues() {
			return minPriceKrw != null || maxPriceKrw != null || minAbvPercent != null || maxAbvPercent != null;
		}
	}
}
