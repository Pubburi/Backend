package com.pubburi.rehearsal;

import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.controller.response.*;
import com.pubburi.rehearsal.persistence.JpaProductQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.*;
import io.swagger.v3.oas.annotations.media.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Only existing GET contracts are registered until product writes and outbox migrate together. */
@RestController
@RequestMapping("/api")
public class JpaProductReadController {
    private final JpaProductQueries products;
    public JpaProductReadController(JpaProductQueries products) {this.products=products;}

    @GetMapping(value="/products",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Search current products with mandatory filters",parameters={
        @Parameter(name="tasteTag",in=ParameterIn.QUERY,style=ParameterStyle.FORM,explode=Explode.TRUE,description="Repeat for all required taste tags; commas stay literal. Unknown/empty tags cannot match.",array=@ArraySchema(maxItems=20,schema=@Schema(type="string",maxLength=40))),
        @Parameter(name="aromaTag",in=ParameterIn.QUERY,style=ParameterStyle.FORM,explode=Explode.TRUE,description="Repeat for all required aroma tags; commas stay literal. Unknown/empty tags cannot match.",array=@ArraySchema(maxItems=20,schema=@Schema(type="string",maxLength=40)))})
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> products(
            @RequestParam(required=false) String type,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="popular") String sort,
            @Parameter(description="Existing page normalization; (page - 1) * normalized size must fit 0..2147483647 OFFSET or returns 400 BAD_REQUEST")
            @RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size,
            @Parameter(schema=@Schema(type="integer",minimum="0",maximum="2147483647")) @RequestParam(required=false) String minPriceKrw,
            @Parameter(schema=@Schema(type="integer",minimum="0",maximum="2147483647")) @RequestParam(required=false) String maxPriceKrw,
            @Parameter(schema=@Schema(type="number",minimum="0",maximum="100",multipleOf=0.01)) @RequestParam(required=false) String minAbvPercent,
            @Parameter(schema=@Schema(type="number",minimum="0",maximum="100",multipleOf=0.01)) @RequestParam(required=false) String maxAbvPercent,
            HttpServletRequest request) {
        var filters=ProductSearchFilters.fromQuery(minPriceKrw,maxPriceKrw,minAbvPercent,maxAbvPercent,
            request.getParameterValues("tasteTag"),request.getParameterValues("aromaTag"));
        var result=products.page(type,q,sort,filters,PageCriteria.of(page,size,12)).map(ResponseMapper::product);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/products/popular")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> popular() {
        return ResponseEntity.ok(ApiResponse.ok(products.popular().stream().map(ResponseMapper::product).toList()));
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> product(@PathVariable int id) {
        var value=products.find(id);
        if(value==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
        return ResponseEntity.ok(ApiResponse.ok(ResponseMapper.product(value)));
    }

    @GetMapping(value="/products/{id}/features",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Read product attributes",description="Existing products without reviewed attributes return null fields. Missing products return 404.")
    public ResponseEntity<ApiResponse<ProductFeaturesResponse>> features(@PathVariable int id) {
        var value=products.features(id);
        if(value==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
        return ResponseEntity.ok(ApiResponse.ok(ProductFeaturesResponse.from(value)));
    }
}
