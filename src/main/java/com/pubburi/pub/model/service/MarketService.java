package com.pubburi.pub.model.service;

import java.util.List;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dto.Market;

public interface MarketService {

	List<Market> getMarketList();

	PageResponse<Market> getMarketPage(PageCriteria criteria);

	Market getMarketById(int id);

	int addMarket(Market market);

	int updateMarket(Market market);

	int removeMarket(int id);

}
