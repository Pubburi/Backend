package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.UserPreferenceValue;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

@Entity(name = "RehearsalPreferenceValue")
@Table(name = "t_user_preference_value", schema = "pubburi")
@IdClass(JpaPreferenceValue.Key.class)
public class JpaPreferenceValue implements Persistable<JpaPreferenceValue.Key> {
    @Id @Column(name = "user_id", length = 100) String userId;
    @Id @Column(length = 8) String kind;
    @Id @Column(name = "value_text", length = 40) String value;
    @Transient private boolean fresh = true;
    protected JpaPreferenceValue() { }
    static JpaPreferenceValue from(String id, UserPreferenceValue value) { var row = new JpaPreferenceValue(); row.userId = id; row.kind = value.kind(); row.value = value.value(); return row; }
    @Override public Key getId() { return new Key(userId, kind, value); }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist void persisted() { fresh = false; }
    public static class Key implements Serializable {
        public String userId; public String kind; public String value;
        public Key() { }
        public Key(String id, String kind, String value) { userId = id; this.kind = kind; this.value = value; }
        @Override public boolean equals(Object other) { return other instanceof Key key && Objects.equals(userId,key.userId) && Objects.equals(kind,key.kind) && Objects.equals(value,key.value); }
        @Override public int hashCode() { return Objects.hash(userId,kind,value); }
    }
}
