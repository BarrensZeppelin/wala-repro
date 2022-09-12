package dk.casa.wala.reproducer;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class UnreachableStreamExample {
	public static void main(String[] args) {
		List<Integer> ints = IntStream.range(0, 10).boxed().collect(Collectors.toList());
		IntStream.range(0, 10).boxed().flatMap(x -> ints.stream()).forEach(System.out::println);
	}
}
