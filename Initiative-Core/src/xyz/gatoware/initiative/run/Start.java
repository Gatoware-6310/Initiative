package xyz.gatoware.initiative.run;

import java.io.File;
import java.io.IOException;

import xyz.gatoware.initiative.Initiative;
//import xyz.gatoware.initiative.ui.InitiativeUi;
import xyz.gatoware.utils.serialization.SaveLoadDevices;
// import xyz.gatoware.initiative.ui.InitiativeUi;

public class Start {
	public static void main(String[] args) throws ClassNotFoundException, IOException {
		Initiative.INSTANCE.initInitiative();
		SaveLoadDevices.load(new File("/home/gatoware/Initiative.devices"));
		//InitiativeUi.main(args);
	}
}
