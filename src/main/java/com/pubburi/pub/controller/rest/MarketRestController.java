package com.pubburi.pub.controller.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.request.MarketRequest;
import com.pubburi.pub.controller.response.MarketResponse;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dto.Market;
import com.pubburi.pub.model.service.MarketService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class MarketRestController {

	private final MarketService marketService;
	private final AccessGuard accessGuard;

	public MarketRestController(MarketService marketService, AccessGuard accessGuard) {
		this.marketService = marketService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/markets")
	public ResponseEntity<ApiResponse<PageResponse<MarketResponse>>> markets(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		PageResponse<MarketResponse> markets = marketService.getMarketPage(PageCriteria.of(page, size, 12))
				.map(ResponseMapper::market);
		return ResponseEntity.ok(ApiResponse.ok(markets));
	}

	@GetMapping("/markets/{id}")
	public ResponseEntity<ApiResponse<MarketResponse>> market(@PathVariable int id) {
		Market market = marketService.getMarketById(id);
		if (market == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Market not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(ResponseMapper.market(market)));
	}

	@PostMapping("/admin/markets")
	public ResponseEntity<ApiResponse<MarketResponse>> addMarket(@Valid @RequestBody MarketRequest request,
			HttpSession session) {
		accessGuard.requireAdmin(session);
		Market market = toMarket(request);
		if (marketService.addMarket(market) <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Market could not be created");
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.message(ResponseMapper.market(market), "created"));
	}

	@PutMapping("/admin/markets/{id}")
	public ResponseEntity<ApiResponse<Boolean>> updateMarket(@PathVariable int id,
			@Valid @RequestBody MarketRequest request, HttpSession session) {
		accessGuard.requireAdmin(session);
		Market market = toMarket(request);
		market.setId(id);
		if (marketService.updateMarket(market) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Market not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(true));
	}

	@DeleteMapping("/admin/markets/{id}")
	public ResponseEntity<ApiResponse<Boolean>> deleteMarket(@PathVariable int id, HttpSession session) {
		accessGuard.requireAdmin(session);
		if (marketService.removeMarket(id) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Market not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(true));
	}

	private Market toMarket(MarketRequest request) {
		Market market = new Market();
		market.setName(request.name());
		market.setLatitude(request.latitude());
		market.setLongitude(request.longitude());
		market.setImage(request.image());
		return market;
	}
}
