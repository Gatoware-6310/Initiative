package xyz.gatoware.utils.serialization;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import xyz.gatoware.initiative.Initiative;
import xyz.gatoware.initiative.devices.Device;
import xyz.gatoware.initiative.devices.External;

public class SaveLoadDevices {
    public static int save(final File file) throws IOException {
        final List<SavedExternal> savedDevices = new ArrayList<SavedExternal>();
        for (final Device device : Initiative.INSTANCE.registry.getDevices()) {
            if (device instanceof External) {
                final External external = (External) device;
                savedDevices.add(new SavedExternal(external.getName(), external.getScriptPath()));
            }
        }

        try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(file))) {
            stream.writeObject(savedDevices);
        }
        return savedDevices.size();
    }

    public static List<External> load(final File file)
            throws IOException, ClassNotFoundException {
        final List<External> devices;
        try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(file))) {
            final Object saved = stream.readObject();
            if (!(saved instanceof List<?>)) {
                throw new IOException("The selected file does not contain an Initiative device list.");
            }

            devices = new ArrayList<External>();
            for (final Object entry : (List<?>) saved) {
                if (!(entry instanceof SavedExternal)) {
                    throw new IOException("The selected file contains an invalid device entry.");
                }
                final SavedExternal external = (SavedExternal) entry;
                devices.add(new External(external.name, external.scriptPath));
            }
        }

        for (final Device device : Initiative.INSTANCE.registry.getDevices()) {
            if (device instanceof External) {
            	Initiative.INSTANCE.registry.removeDevice(device);
            }
        }
        for (final External device : devices) {
        	Initiative.INSTANCE.registry.registerDevice(device);
        }
        return devices;
    }

    private static final class SavedExternal implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String name;
        private final String scriptPath;

        private SavedExternal(final String name, final String scriptPath) {
            this.name = name;
            this.scriptPath = scriptPath;
        }
    }
}
