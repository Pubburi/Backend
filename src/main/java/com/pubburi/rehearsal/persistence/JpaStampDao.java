package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.StampDao;
import com.pubburi.pub.model.dto.Stamp;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaStampDao implements StampDao {
    private final StampRepository stamps;
    public JpaStampDao(StampRepository stamps) { this.stamps = stamps; }
    @Override public List<Stamp> selectByUserId(String userId) { return stamps.byUser(userId); }
    @Override @Transactional public int insert(Stamp stamp) {
        var row = stamps.saveAndFlush(JpaStamp.from(stamp)); stamp.setId(row.id); return 1;
    }
}
