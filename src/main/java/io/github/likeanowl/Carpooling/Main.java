package io.github.likeanowl.Carpooling;

import io.github.likeanowl.Carpooling.Boot.Bootstraper;
import jade.Boot;

public class Main {
	public static void main(String[] args) {
		String[] s = {"-gui", "start:" + Bootstraper.class.getName()};
		Boot.main(s);
		System.out.println("System launched");
	}
}
