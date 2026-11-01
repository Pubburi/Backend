package com.pubburi.pub.controller.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

public record ProductSearchFilters(Integer minPriceKrw, Integer maxPriceKrw, BigDecimal minAbvPercent, BigDecimal maxAbvPercent,
		List<String> tasteTags, List<String> aromaTags) {
	public ProductSearchFilters {
		if ((minPriceKrw != null && minPriceKrw < 0) || (maxPriceKrw != null && maxPriceKrw < 0)
				|| (minPriceKrw != null && maxPriceKrw != null && minPriceKrw > maxPriceKrw))
			throw new IllegalArgumentException("Price range must be nonnegative and ordered");
		checkAbv(minAbvPercent); checkAbv(maxAbvPercent);
		if (minAbvPercent != null && maxAbvPercent != null && minAbvPercent.compareTo(maxAbvPercent) > 0)
			throw new IllegalArgumentException("ABV range must be ordered");
		tasteTags = tags(tasteTags); aromaTags = tags(aromaTags);
	}

	public static ProductSearchFilters none() { return new ProductSearchFilters(null, null, null, null, List.of(), List.of()); }

	public static ProductSearchFilters fromQuery(String minPriceKrw, String maxPriceKrw, String minAbvPercent, String maxAbvPercent,
			String[] tasteTags, String[] aromaTags) {
		return new ProductSearchFilters(price(minPriceKrw), price(maxPriceKrw), abv(minAbvPercent), abv(maxAbvPercent),
				tasteTags == null ? List.of() : java.util.Arrays.asList(tasteTags),
				aromaTags == null ? List.of() : java.util.Arrays.asList(aromaTags));
	}

	private static Integer price(String value) {
		if (value == null) return null;
		if (!value.matches("[0-9]{1,10}")) throw new IllegalArgumentException("Price must be a nonnegative integer");
		try { return Integer.valueOf(value); }
		catch (NumberFormatException invalid) { throw new IllegalArgumentException("Price exceeds the supported integer range"); }
	}

	private static BigDecimal abv(String value) {
		if (value == null) return null;
		if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,2})?"))
			throw new IllegalArgumentException("ABV must be a decimal between 0 and 100 with at most two fractional digits");
		return new BigDecimal(value);
	}

	private static void checkAbv(BigDecimal value) {
		if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0 || value.scale() > 2))
			throw new IllegalArgumentException("ABV must be between 0 and 100 with at most two fractional digits");
	}

	private static List<String> tags(List<String> values) {
		if (values == null || values.size() > 20) throw new IllegalArgumentException("At most 20 tags per kind are allowed");
		TreeSet<String> normalized = new TreeSet<>();
		for (String value : values) {
			if (value == null || value.isBlank() || value.length() > 40)
				throw new IllegalArgumentException("Tags must be nonblank strings of at most 40 characters");
			String tag = value.strip().toLowerCase(Locale.ROOT);
			if (tag.length() > 40 || !normalized.add(tag)) throw new IllegalArgumentException("Tags must be unique after normalization");
		}
		return List.copyOf(normalized);
	}
}
