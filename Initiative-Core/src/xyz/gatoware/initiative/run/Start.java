package xyz.gatoware.initiative.run;

import java.io.IOException;

import xyz.gatoware.initiative.Initiative;

// import xyz.gatoware.initiative.ui.InitiativeUi;

public class Start {
	public static void main(String[] args) throws ClassNotFoundException, IOException {
		Initiative.INSTANCE.initInitiative();
		//InitiativeUi.main(args);
	}
}
