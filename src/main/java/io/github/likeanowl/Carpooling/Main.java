package io.github.likeanowl.Carpooling;

import jade.Boot;

public class Main {
	public static void main(String[] args) {
		String[] s = {"-gui", "start:Bootstraper"};
		Boot.main(s);
		System.out.println("System launched");
	}
}
