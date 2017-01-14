package io.github.likeanowl.Carpooling.Boot;

import jade.core.Agent;
import jade.wrapper.AgentController;
import jade.wrapper.ControllerException;
import jade.wrapper.PlatformController;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class Bootstraper extends Agent {
	@Override
	protected void setup() {
		String fileName = "data.txt";
		File file = new File(fileName);
		try (BufferedReader in = new BufferedReader(new FileReader(file.getAbsoluteFile()))) {
			PlatformController controller = getContainerController();
			int travelersCount = Integer.parseInt(in.readLine());
			for (int i = 0; i < travelersCount; i++) {
				try {
					String[] travelerInfo = in.readLine().split(" ");
					AgentController traveler = controller.createNewAgent("Traveller_" + i
							, "TravellerAgent", travelerInfo);
					traveler.start();
				} catch (ControllerException ex) {
					System.err.println("Exception while adding traveller agent Traveller_" + i);
					ex.printStackTrace();
				}
			}
		} catch (IOException ex) {
			System.err.println("IOException in Bootstraper agent");
			ex.printStackTrace();
		}
		doDelete();
	}
}
