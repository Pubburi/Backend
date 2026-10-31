package com.pubburi.pub.controller.response;

import java.math.BigDecimal;
import java.util.List;

import com.pubburi.pub.model.dto.UserPreferences;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"surveyStatus", "softPreferences", "hardConstraints"})
public record UserPreferencesResponse(@Schema(allowableValues = {"NOT_STARTED", "SKIPPED", "COMPLETED"}) String surveyStatus, PreferenceSoftResponse softPreferences,
		PreferenceHardResponse hardConstraints) {
	public static UserPreferencesResponse from(UserPreferences value) {
		return new UserPreferencesResponse(value.getSurveyStatus(),
				new PreferenceSoftResponse(value.getCategories(), value.getTasteTags(), value.getAromaTags(), value.getTargetAbvPercent()),
				new PreferenceHardResponse(value.getMinPriceKrw(), value.getMaxPriceKrw(), value.getMinAbvPercent(), value.getMaxAbvPercent()));
	}

	@Schema(requiredProperties = {"categories", "tasteTags", "aromaTags", "targetAbvPercent"})
	public record PreferenceSoftResponse(
			@Schema(types = {"array", "null"}) List<String> categories,
			@Schema(types = {"array", "null"}) List<String> tasteTags,
			@Schema(types = {"array", "null"}) List<String> aromaTags,
			@Schema(types = {"number", "null"}) BigDecimal targetAbvPercent) { }

	@Schema(requiredProperties = {"minPriceKrw", "maxPriceKrw", "minAbvPercent", "maxAbvPercent"})
	public record PreferenceHardResponse(
			@Schema(types = {"integer", "null"}) Integer minPriceKrw,
			@Schema(types = {"integer", "null"}) Integer maxPriceKrw,
			@Schema(types = {"number", "null"}) BigDecimal minAbvPercent,
			@Schema(types = {"number", "null"}) BigDecimal maxAbvPercent) { }
}
