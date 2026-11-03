package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.UserPreferenceDao;
import com.pubburi.pub.model.dto.UserPreferences;
import com.pubburi.pub.model.dto.UserPreferenceValue;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaUserPreferenceDao implements UserPreferenceDao {
    private final PreferenceRepository headers;
    private final PreferenceValueRepository values;
    public JpaUserPreferenceDao(PreferenceRepository headers, PreferenceValueRepository values) { this.headers = headers; this.values = values; }
    private UUID epoch(String value) { try { return UUID.fromString(value); } catch (IllegalArgumentException | NullPointerException invalid) { return null; } }
    @Override public UserPreferences selectOwned(String userId, String authEpoch) {
        UUID epoch = epoch(authEpoch); return epoch == null ? null : headers.owned(userId, epoch).map(PreferenceHeaderView::toDto).orElse(null);
    }
    @Override public List<UserPreferenceValue> selectOwnedValues(String userId, String authEpoch) {
        UUID epoch = epoch(authEpoch); return epoch == null ? List.of() : values.owned(userId, epoch);
    }
    @Override @Transactional public int upsert(UserPreferences value) {
        // The original service already holds the full User lock and has rechecked its epoch.
        var row = headers.findById(value.getUserId()).orElseGet(() -> JpaPreference.from(value));
        row.apply(value); headers.saveAndFlush(row); return 1;
    }
    @Override @Transactional public int deleteValues(String userId) { return values.deleteForUser(userId); }
    @Override @Transactional public int insertValues(String userId, List<UserPreferenceValue> rows) {
        if (rows.isEmpty()) return 0;
        return values.saveAllAndFlush(rows.stream().map(value -> JpaPreferenceValue.from(userId, value)).toList()).size();
    }
}
