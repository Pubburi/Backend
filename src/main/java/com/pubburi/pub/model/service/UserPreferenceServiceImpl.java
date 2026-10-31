package com.pubburi.pub.model.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dao.UserPreferenceDao;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.dto.UserPreferences;
import com.pubburi.pub.model.dto.UserPreferenceValue;

@Service
public class UserPreferenceServiceImpl implements UserPreferenceService {
	private static final Set<String> CATEGORIES = Set.of("탁주", "약 · 청주", "과실주", "증류주", "위스키", "양주", "맥주", "사케");
	private static final Set<String> STATUSES = Set.of("NOT_STARTED", "SKIPPED", "COMPLETED");
	private final UserDao userDao;
	private final UserPreferenceDao preferenceDao;

	public UserPreferenceServiceImpl(UserDao userDao, UserPreferenceDao preferenceDao) {
		this.userDao = userDao;
		this.preferenceDao = preferenceDao;
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public UserPreferences getOwned(String userId, String authEpoch) {
		if (!identityPresent(userId, authEpoch)) return null;
		// Both reads bind the incarnation; no row means expired identity, not an empty survey.
		UserPreferences preferences = preferenceDao.selectOwned(userId, authEpoch);
		if (preferences == null) return null;
		List<UserPreferenceValue> values = preferenceDao.selectOwnedValues(userId, authEpoch);
		preferences.setCategories(preferences.isCategoriesKnown() ? valuesOf(values, "CATEGORY") : null);
		preferences.setTasteTags(preferences.isTasteKnown() ? valuesOf(values, "TASTE") : null);
		preferences.setAromaTags(preferences.isAromaKnown() ? valuesOf(values, "AROMA") : null);
		return preferences;
	}

	@Override
	@Transactional
	public UserPreferences replaceOwned(String userId, String authEpoch, UserPreferences changes) {
		if (!identityPresent(userId, authEpoch) || changes == null) return null;
		User locked = userDao.selectByIdForUpdate(userId);
		if (locked == null || !Objects.equals(authEpoch, locked.getAuthEpoch())) return null;
		// Do not transfer an in-flight old session's input to a recreated/reset account.
		UserPreferences normalized = normalize(userId, changes);
		preferenceDao.upsert(normalized);
		preferenceDao.deleteValues(userId);
		List<UserPreferenceValue> values = new ArrayList<>();
		append(values, "CATEGORY", normalized.getCategories());
		append(values, "TASTE", normalized.getTasteTags());
		append(values, "AROMA", normalized.getAromaTags());
		if (!values.isEmpty() && preferenceDao.insertValues(userId, values) != values.size()) {
			throw new IllegalStateException("Explicit preferences could not be replaced");
		}
		return normalized;
	}

	private UserPreferences normalize(String userId, UserPreferences value) {
		if (value.getSurveyStatus() == null || !STATUSES.contains(value.getSurveyStatus())) throw new IllegalArgumentException("Invalid survey status");
		UserPreferences result = new UserPreferences();
		result.setUserId(userId);
		result.setSurveyStatus(value.getSurveyStatus());
		result.setCategories(normalizeValues(value.getCategories(), true));
		result.setTasteTags(normalizeValues(value.getTasteTags(), false));
		result.setAromaTags(normalizeValues(value.getAromaTags(), false));
		result.setCategoriesKnown(result.getCategories() != null);
		result.setTasteKnown(result.getTasteTags() != null);
		result.setAromaKnown(result.getAromaTags() != null);
		result.setTargetAbvPercent(value.getTargetAbvPercent());
		result.setMinPriceKrw(value.getMinPriceKrw());
		result.setMaxPriceKrw(value.getMaxPriceKrw());
		result.setMinAbvPercent(value.getMinAbvPercent());
		result.setMaxAbvPercent(value.getMaxAbvPercent());
		for (BigDecimal number : new BigDecimal[] {result.getTargetAbvPercent(), result.getMinAbvPercent(), result.getMaxAbvPercent()}) {
			if (number != null && (number.signum() < 0 || number.compareTo(new BigDecimal("100")) > 0 || number.scale() > 2)) {
				throw new IllegalArgumentException("ABV requires a number from 0 to 100 with at most two decimals");
			}
		}
		if ((result.getMinPriceKrw() != null && result.getMinPriceKrw() < 0)
				|| (result.getMaxPriceKrw() != null && result.getMaxPriceKrw() < 0)
				|| (result.getMinPriceKrw() != null && result.getMaxPriceKrw() != null && result.getMinPriceKrw() > result.getMaxPriceKrw())
				|| (result.getMinAbvPercent() != null && result.getMaxAbvPercent() != null
						&& result.getMinAbvPercent().compareTo(result.getMaxAbvPercent()) > 0)) {
			throw new IllegalArgumentException("Hard ranges must be nonnegative and ordered");
		}
		if (!"COMPLETED".equals(result.getSurveyStatus()) && (result.isCategoriesKnown() || result.isTasteKnown()
				|| result.isAromaKnown() || result.getTargetAbvPercent() != null || result.getMinPriceKrw() != null
				|| result.getMaxPriceKrw() != null || result.getMinAbvPercent() != null || result.getMaxAbvPercent() != null)) {
			throw new IllegalArgumentException("Skipped or not-started surveys require unknown values");
		}
		return result;
	}

	private List<String> normalizeValues(List<String> values, boolean categories) {
		if (values == null) return null;
		if (values.size() > (categories ? 8 : 20)) throw new IllegalArgumentException("Too many preference values");
		TreeSet<String> result = new TreeSet<>();
		for (String raw : values) {
			if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Preference values must be nonblank strings");
			String normalized = categories ? raw.strip() : raw.strip().toLowerCase(Locale.ROOT);
			if (normalized.length() > (categories ? 20 : 40) || categories && !CATEGORIES.contains(normalized)
					|| !result.add(normalized)) throw new IllegalArgumentException("Unknown category or duplicate preference value");
		}
		return List.copyOf(result);
	}

	private List<String> valuesOf(List<UserPreferenceValue> values, String kind) {
		return values.stream().filter(value -> kind.equals(value.kind())).map(UserPreferenceValue::value).sorted().toList();
	}
	private void append(List<UserPreferenceValue> target, String kind, List<String> values) {
		if (values != null) values.forEach(value -> target.add(new UserPreferenceValue(kind, value)));
	}
	private boolean identityPresent(String userId, String authEpoch) {
		return userId != null && !userId.isBlank() && authEpoch != null && !authEpoch.isBlank();
	}
}
