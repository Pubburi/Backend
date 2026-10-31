package com.pubburi.pub.controller.rest;

import java.util.List;

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
import com.pubburi.pub.controller.request.ProductRequest;
import com.pubburi.pub.controller.response.ProductResponse;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.service.ProductService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class ProductRestController {

	private final ProductService productService;
	private final AccessGuard accessGuard;

	public ProductRestController(ProductService productService, AccessGuard accessGuard) {
		this.productService = productService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/products")
	public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> products(@RequestParam(required = false) String type,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "popular") String sort,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		PageResponse<ProductResponse> products = productService.searchProducts(type, q, sort,
				PageCriteria.of(page, size, 12)).map(ResponseMapper::product);
		return ResponseEntity.ok(ApiResponse.ok(products));
	}

	@GetMapping("/products/popular")
	public ResponseEntity<ApiResponse<List<ProductResponse>>> popularProducts() {
		List<ProductResponse> products = productService.getPopularProducts().stream().map(ResponseMapper::product)
				.toList();
		return ResponseEntity.ok(ApiResponse.ok(products));
	}

	@GetMapping("/products/{id}")
	public ResponseEntity<ApiResponse<ProductResponse>> product(@PathVariable int id) {
		Product product = productService.getProductById(id);
		if (product == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(ResponseMapper.product(product)));
	}

	@PostMapping("/admin/products")
	public ResponseEntity<ApiResponse<ProductResponse>> addProduct(@Valid @RequestBody ProductRequest request,
			HttpSession session) {
		accessGuard.requireAdmin(session);
		Product product = toProduct(request);
		if (productService.addProduct(product) <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product could not be created");
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.message(ResponseMapper.product(product), "created"));
	}

	@PutMapping("/admin/products/{id}")
	public ResponseEntity<ApiResponse<Boolean>> updateProduct(@PathVariable int id,
			@Valid @RequestBody ProductRequest request, HttpSession session) {
		accessGuard.requireAdmin(session);
		Product product = toProduct(request);
		product.setId(id);
		if (productService.updateProduct(product) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(true));
	}

	@DeleteMapping("/admin/products/{id}")
	public ResponseEntity<ApiResponse<Boolean>> deleteProduct(@PathVariable int id, HttpSession session) {
		accessGuard.requireAdmin(session);
		if (productService.removeProduct(id) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(true));
	}

	private Product toProduct(ProductRequest request) {
		Product product = new Product();
		product.setName(request.name());
		product.setType(request.type());
		product.setPrice(request.price());
		product.setImg(request.img());
		product.setAbv(request.abv());
		product.setOrderCount(request.orderCount());
		return product;
	}
}
