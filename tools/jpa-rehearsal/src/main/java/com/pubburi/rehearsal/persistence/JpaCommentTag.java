package com.pubburi.rehearsal.persistence;

import java.io.Serializable;
import java.util.Objects;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

@Entity(name="RehearsalCommentTag")
@Table(name="t_comment_taste_tag",schema="pubburi")
@IdClass(JpaCommentTag.Key.class)
public class JpaCommentTag implements Persistable<JpaCommentTag.Key> {
    @Id @Column(name="comment_id") Integer commentId;
    @Id @Column(length=40) String tag;
    @Transient private boolean fresh=true;
    protected JpaCommentTag() { }
    static JpaCommentTag from(int id,String tag) {var row=new JpaCommentTag();row.commentId=id;row.tag=tag;return row;}
    @Override public Key getId(){return new Key(commentId,tag);}
    @Override public boolean isNew(){return fresh;}
    @PostLoad @PostPersist void persisted(){fresh=false;}

    public static class Key implements Serializable {
        public Integer commentId;
        public String tag;
        public Key() { }
        public Key(Integer id,String tag){commentId=id;this.tag=tag;}
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(commentId,key.commentId)
                && Objects.equals(tag,key.tag);
        }
        @Override public int hashCode() { return Objects.hash(commentId,tag); }
    }
}
