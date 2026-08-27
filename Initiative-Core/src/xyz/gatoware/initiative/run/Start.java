package xyz.gatoware.initiative.run;

import java.io.IOException;

import xyz.gatoware.initiative.Initiative;

// import xyz.gatoware.initiative.ui.InitiativeUi;

public class Start {
	public static void main(String[] args) throws ClassNotFoundException, IOException {
		System.setProperty("java.net.preferIPv4Stack", "true");
		Initiative.INSTANCE.initInitiative();
		//InitiativeUi.main(args);
	}
}
