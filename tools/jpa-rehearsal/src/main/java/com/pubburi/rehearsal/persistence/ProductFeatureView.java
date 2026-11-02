package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.ProductFeatures;

public record ProductFeatureView(int productId,Integer volumeMl,String description,boolean tasteKnown,
        boolean aromaKnown,String sourceName,String sourceUrl,String sourceNote) {
    ProductFeatures dto() {
        var value=new ProductFeatures();
        value.setProductId(productId);value.setVolumeMl(volumeMl);value.setDescription(description);
        value.setTasteKnown(tasteKnown);value.setAromaKnown(aromaKnown);
        value.setSourceName(sourceName);value.setSourceUrl(sourceUrl);value.setSourceNote(sourceNote);
        return value;
    }
}
