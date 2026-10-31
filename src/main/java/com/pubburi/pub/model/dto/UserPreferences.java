package com.pubburi.pub.model.dto;

import java.math.BigDecimal;
import java.util.List;

/** Private explicit input, scoped internally by the current account incarnation. */
public class UserPreferences {
	private String userId;
	private String surveyStatus;
	private boolean categoriesKnown;
	private boolean tasteKnown;
	private boolean aromaKnown;
	private List<String> categories;
	private List<String> tasteTags;
	private List<String> aromaTags;
	private BigDecimal targetAbvPercent;
	private Integer minPriceKrw;
	private Integer maxPriceKrw;
	private BigDecimal minAbvPercent;
	private BigDecimal maxAbvPercent;

	public String getUserId() { return userId; }
	public void setUserId(String userId) { this.userId = userId; }
	public String getSurveyStatus() { return surveyStatus; }
	public void setSurveyStatus(String surveyStatus) { this.surveyStatus = surveyStatus; }
	public boolean isCategoriesKnown() { return categoriesKnown; }
	public void setCategoriesKnown(boolean categoriesKnown) { this.categoriesKnown = categoriesKnown; }
	public boolean isTasteKnown() { return tasteKnown; }
	public void setTasteKnown(boolean tasteKnown) { this.tasteKnown = tasteKnown; }
	public boolean isAromaKnown() { return aromaKnown; }
	public void setAromaKnown(boolean aromaKnown) { this.aromaKnown = aromaKnown; }
	public List<String> getCategories() { return categories; }
	public void setCategories(List<String> categories) { this.categories = categories; }
	public List<String> getTasteTags() { return tasteTags; }
	public void setTasteTags(List<String> tasteTags) { this.tasteTags = tasteTags; }
	public List<String> getAromaTags() { return aromaTags; }
	public void setAromaTags(List<String> aromaTags) { this.aromaTags = aromaTags; }
	public BigDecimal getTargetAbvPercent() { return targetAbvPercent; }
	public void setTargetAbvPercent(BigDecimal targetAbvPercent) { this.targetAbvPercent = targetAbvPercent; }
	public Integer getMinPriceKrw() { return minPriceKrw; }
	public void setMinPriceKrw(Integer minPriceKrw) { this.minPriceKrw = minPriceKrw; }
	public Integer getMaxPriceKrw() { return maxPriceKrw; }
	public void setMaxPriceKrw(Integer maxPriceKrw) { this.maxPriceKrw = maxPriceKrw; }
	public BigDecimal getMinAbvPercent() { return minAbvPercent; }
	public void setMinAbvPercent(BigDecimal minAbvPercent) { this.minAbvPercent = minAbvPercent; }
	public BigDecimal getMaxAbvPercent() { return maxAbvPercent; }
	public void setMaxAbvPercent(BigDecimal maxAbvPercent) { this.maxAbvPercent = maxAbvPercent; }
}
