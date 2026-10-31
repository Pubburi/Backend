package com.pubburi.pub.model.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.pubburi.pub.model.dao.StampDao;
import com.pubburi.pub.model.dto.Stamp;

@Service
public class StampServiceImpl implements StampService {

	private final StampDao stampDao;

	public StampServiceImpl(StampDao stampDao) {
		this.stampDao = stampDao;
	}

	@Override
	public List<Stamp> getStampsByUserId(String userId) {
		if (userId == null || userId.isEmpty()) {
			return null;
		}
		return stampDao.selectByUserId(userId);
	}
}
