package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GradeServiceTests {
	private final GradeService gradeService = new GradeService();

	@Test
	void gradeBoundariesMatchExistingProfileShape() {
		assertThat(gradeService.gradeFor(0))
				.extracting("title", "img", "step", "stepMax", "to")
				.containsExactly("씨앗", "seeds.png", 0, 10, 1);

		assertThat(gradeService.gradeFor(50))
				.extracting("title", "img", "step", "stepMax", "to")
				.containsExactly("씨앗", "seeds.png", 5, 10, 1);

		assertThat(gradeService.gradeFor(51))
				.extracting("title", "img", "step", "stepMax")
				.containsExactly("꽃", "flower.png", 1, 15);

		assertThat(gradeService.gradeFor(126))
				.extracting("title", "img", "stepMax")
				.containsExactly("열매", "coffee_fruit.png", 20);

		assertThat(gradeService.gradeFor(351))
				.extracting("title", "img", "step")
				.containsExactly("커피나무", "coffee_tree.png", 1);
	}
}
