package com.pubburi.pub.model.service;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.response.SearchPhraseResponse;
import com.pubburi.pub.controller.response.SearchPhraseResponse.Source;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.model.dto.UserPreferences;
import com.pubburi.pub.recommendation.SearchPhraseContract;
import com.pubburi.pub.recommendation.SearchPhraseGateway;

@Service
public class SearchPhraseService {
    private final UserPreferenceService preferences;
    private final SearchPhraseGateway gateway;
    private final SearchPhraseContract contract;
    public SearchPhraseService(UserPreferenceService preferences, SearchPhraseGateway gateway, ObjectMapper mapper) {
        this.preferences = preferences; this.gateway = gateway; this.contract = new SearchPhraseContract(mapper);
    }
    // Each canonical read owns its short RR transaction; remote I/O is outside both.
    public SearchPhraseResponse suggest(String id, String epoch, List<String> fields, int limit) {
        UserPreferencesResponse snapshot = owned(id, epoch);
        var response = gateway.suggest(snapshot, List.copyOf(fields), limit);
        var current = owned(id, epoch);
        if (!same(snapshot, current)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Preferences changed; request search phrases again");
        if (response.isPresent()) {
            try { return new SearchPhraseResponse(Source.PROVIDER, contract.validate(response.get(), snapshot, fields, limit)); }
            catch (SearchPhraseContract.UnsupportedConditionsException unsupported) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stored preferences cannot be used as search conditions");
            }
            catch (IllegalArgumentException invalid) { /* fail closed; no local phrase or weaker query */ }
        }
        return new SearchPhraseResponse(Source.UNAVAILABLE, null);
    }
    private UserPreferencesResponse owned(String id, String epoch) {
        UserPreferences value = preferences.getOwned(id, epoch);
        if (value == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account session is no longer current");
        return UserPreferencesResponse.from(value);
    }
    private boolean same(UserPreferencesResponse a, UserPreferencesResponse b) {
        var x = a.softPreferences(); var y = b.softPreferences(); var m = a.hardConstraints(); var n = b.hardConstraints();
        return a.surveyStatus().equals(b.surveyStatus()) && java.util.Objects.equals(x.categories(), y.categories())
                && java.util.Objects.equals(x.tasteTags(), y.tasteTags()) && java.util.Objects.equals(x.aromaTags(), y.aromaTags())
                && equalNumber(x.targetAbvPercent(), y.targetAbvPercent())
                && java.util.Objects.equals(m.minPriceKrw(), n.minPriceKrw()) && java.util.Objects.equals(m.maxPriceKrw(), n.maxPriceKrw())
                && equalNumber(m.minAbvPercent(), n.minAbvPercent()) && equalNumber(m.maxAbvPercent(), n.maxAbvPercent());
    }
    private boolean equalNumber(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
}
