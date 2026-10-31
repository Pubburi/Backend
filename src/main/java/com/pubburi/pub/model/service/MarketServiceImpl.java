package com.pubburi.pub.model.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dao.MarketDao;
import com.pubburi.pub.model.dto.Market;

@Service
public class MarketServiceImpl implements MarketService {

	private final MarketDao marketDao;

	public MarketServiceImpl(MarketDao marketDao) {
		this.marketDao = marketDao;
	}

	@Override
	public List<Market> getMarketList() {
		return marketDao.selectAll();
	}

	@Override
	public PageResponse<Market> getMarketPage(PageCriteria criteria) {
		List<Market> markets = marketDao.selectPaged(criteria.size(), criteria.offset());
		return PageResponse.of(markets, criteria, marketDao.countAll());
	}

	@Override
	public Market getMarketById(int id) {
		if (id <= 0) {
			return null;
		}
		return marketDao.selectById(id);
	}

	@Override
	@Transactional
	public int addMarket(Market market) {
		if (market == null || market.getName() == null || market.getName().isBlank()) {
			return 0;
		}
		return marketDao.insert(market);
	}

	@Override
	@Transactional
	public int updateMarket(Market market) {
		if (market == null || market.getId() <= 0) {
			return 0;
		}
		return marketDao.update(market);
	}

	@Override
	@Transactional
	public int removeMarket(int id) {
		if (id <= 0) {
			return 0;
		}
		return marketDao.delete(id);
	}
}
