package com.pubburi.pub.model.dto;

import java.util.List;

/** Active catalog attributes, separate from immutable order snapshots. */
public class ProductFeatures {
	private int productId;
	private Integer volumeMl;
	private String description;
	private boolean tasteKnown;
	private boolean aromaKnown;
	private List<String> tasteTags;
	private List<String> aromaTags;
	private String sourceName;
	private String sourceUrl;
	private String sourceNote;

	public int getProductId() { return productId; }
	public void setProductId(int productId) { this.productId = productId; }
	public Integer getVolumeMl() { return volumeMl; }
	public void setVolumeMl(Integer volumeMl) { this.volumeMl = volumeMl; }
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }
	public boolean isTasteKnown() { return tasteKnown; }
	public void setTasteKnown(boolean tasteKnown) { this.tasteKnown = tasteKnown; }
	public boolean isAromaKnown() { return aromaKnown; }
	public void setAromaKnown(boolean aromaKnown) { this.aromaKnown = aromaKnown; }
	public List<String> getTasteTags() { return tasteTags; }
	public void setTasteTags(List<String> tasteTags) { this.tasteTags = tasteTags; }
	public List<String> getAromaTags() { return aromaTags; }
	public void setAromaTags(List<String> aromaTags) { this.aromaTags = aromaTags; }
	public String getSourceName() { return sourceName; }
	public void setSourceName(String sourceName) { this.sourceName = sourceName; }
	public String getSourceUrl() { return sourceUrl; }
	public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
	public String getSourceNote() { return sourceNote; }
	public void setSourceNote(String sourceNote) { this.sourceNote = sourceNote; }
}
