package com.pubburi.pub.model.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.pubburi.pub.controller.response.GradeResponse;

@Service
public class GradeService {
	private static final List<Level> LEVELS = List.of(
			new Level("", 0, 0, ""),
			new Level("씨앗", 10, 50, "seeds.png"),
			new Level("꽃", 15, 125, "flower.png"),
			new Level("열매", 20, 225, "coffee_fruit.png"),
			new Level("커피콩", 25, 350, "coffee_beans.png"),
			new Level("커피나무", Integer.MAX_VALUE, Integer.MAX_VALUE, "coffee_tree.png"));

	public GradeResponse gradeFor(Integer stamp) {
		int safeStamp = stamp == null ? 0 : stamp;
		for (int i = 1; i < LEVELS.size(); i++) {
			Level current = LEVELS.get(i);
			if (safeStamp <= current.max()) {
				Level previous = LEVELS.get(i - 1);
				int step = (int) Math.ceil((double) (safeStamp - previous.max()) / current.unit());
				int to = (current.max() - safeStamp) % current.unit() + 1;
				return new GradeResponse(current.img(), step, current.unit(), to, current.title());
			}
		}
		Level last = LEVELS.get(LEVELS.size() - 1);
		return new GradeResponse(last.img(), 1, last.unit(), 1, last.title());
	}

	private record Level(String title, int unit, int max, String img) {
	}
}
