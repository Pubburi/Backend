package com.pubburi.pub.recommendation;

import java.util.List;
import java.util.Optional;

public interface BaselineGateway {
	Optional<List<Integer>> recommend(List<Integer> candidates, int limit);
}
