package xyz.gatoware.initiative.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import xyz.gatoware.initiative.Initiative;
import xyz.gatoware.initiative.actions.Action;
import xyz.gatoware.initiative.actions.ActionArgument;
import xyz.gatoware.initiative.devices.Device;
import xyz.gatoware.initiative.devices.External;

/** A small Swing interface for registering and controlling Python externals. */
public final class InitiativeUi extends JFrame {
    private static final long serialVersionUID = 1L;

    private final JTextField deviceName = new JTextField(16);
    private final JTextField scriptPath = new JTextField(28);
    private final JPanel devices = new JPanel();
    private final JTextArea output = new JTextArea(6, 60);
    private final Map<External, JLabel> statusLabels = new LinkedHashMap<External, JLabel>();

    public InitiativeUi() {
        super("Initiative");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(720, 520));
        setLayout(new BorderLayout(8, 8));

        add(createRegistrationPanel(), BorderLayout.NORTH);

        devices.setLayout(new BoxLayout(devices, BoxLayout.Y_AXIS));
        add(new JScrollPane(devices), BorderLayout.CENTER);

        output.setEditable(false);
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        add(new JScrollPane(output), BorderLayout.SOUTH);
        new Timer(30_000, event -> refreshStatuses()).start();
        pack();
        setLocationByPlatform(true);
    }

    public static void main(final String[] arguments) {
        SwingUtilities.invokeLater(() -> new InitiativeUi().setVisible(true));
    }

    private JPanel createRegistrationPanel() {
        final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Register Python External"));
        panel.add(new JLabel("Name"));
        panel.add(deviceName);
        panel.add(new JLabel("Script"));
        panel.add(scriptPath);

        final JButton register = new JButton("Discover and Register");
        register.addActionListener(event -> registerExternal(register));
        panel.add(register);

        final JButton save = new JButton("Save Devices");
        save.addActionListener(event -> saveDevices());
        panel.add(save);

        final JButton load = new JButton("Load Devices");
        load.addActionListener(event -> loadDevices());
        panel.add(load);
        return panel;
    }

    private void registerExternal(final JButton registerButton) {
        final String name = deviceName.getText().trim();
        final String script = scriptPath.getText().trim();
        if (name.isEmpty() || script.isEmpty()) {
            appendOutput("A device name and Python script path are required.");
            return;
        }

        registerButton.setEnabled(false);
        new SwingWorker<Registration, Void>() {
            @Override
            protected Registration doInBackground() {
                final External external = new External(name, script);
                return new Registration(external, external.capabilities());
            }

            @Override
            protected void done() {
                registerButton.setEnabled(true);
                try {
                    final Registration registration = get();
                    addRegistration(registration);
                } catch (final Exception exception) {
                    appendOutput("Could not register external: " + rootMessage(exception));
                }
            }
        }.execute();
    }

    private void addRegistration(final Registration registration) {
        Initiative.INSTANCE.registerDevice(registration.external);
        devices.add(createDevicePanel(registration));
        devices.revalidate();
        devices.repaint();
        appendOutput("Registered " + registration.external.getName() + ".");
        refreshStatus(registration.external, statusLabels.get(registration.external));
    }

    private void saveDevices() {
        final JFileChooser chooser = new JFileChooser();
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        final List<SavedExternal> savedDevices = new ArrayList<SavedExternal>();
        for (final Device device : Initiative.INSTANCE.getDevices()) {
            if (device instanceof External) {
                final External external = (External) device;
                savedDevices.add(new SavedExternal(external.getName(), external.getScriptPath()));
            }
        }

        final File file = chooser.getSelectedFile();
        try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(file))) {
            stream.writeObject(savedDevices);
            appendOutput("Saved " + savedDevices.size() + " device(s) to " + file.getName() + ".");
        } catch (final IOException exception) {
            appendOutput("Could not save devices: " + rootMessage(exception));
        }
    }

    private void loadDevices() {
        final JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final File file = chooser.getSelectedFile();

        new SwingWorker<List<Registration>, Void>() {
            @Override
            protected List<Registration> doInBackground() throws Exception {
                final List<SavedExternal> savedDevices = readDevices(file);
                final List<Registration> registrations = new ArrayList<Registration>();
                for (final SavedExternal saved : savedDevices) {
                    final External external = new External(saved.name, saved.scriptPath);
                    registrations.add(new Registration(external, external.capabilities()));
                }
                return registrations;
            }

            @Override
            protected void done() {
                try {
                    final List<Registration> registrations = get();
                    clearRegisteredExternals();
                    for (final Registration registration : registrations) {
                        addRegistration(registration);
                    }
                    appendOutput("Loaded " + registrations.size() + " device(s) from " + file.getName() + ".");
                } catch (final Exception exception) {
                    appendOutput("Could not load devices: " + rootMessage(exception));
                }
            }
        }.execute();
    }

    private List<SavedExternal> readDevices(final File file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(file))) {
            final Object saved = stream.readObject();
            if (!(saved instanceof List<?>)) {
                throw new IOException("The selected file does not contain an Initiative device list.");
            }
            final List<SavedExternal> devices = new ArrayList<SavedExternal>();
            for (final Object entry : (List<?>) saved) {
                if (!(entry instanceof SavedExternal)) {
                    throw new IOException("The selected file contains an invalid device entry.");
                }
                devices.add((SavedExternal) entry);
            }
            return devices;
        }
    }

    private void clearRegisteredExternals() {
        for (final Device device : Initiative.INSTANCE.getDevices()) {
            if (device instanceof External) {
                Initiative.INSTANCE.removeDevice(device);
            }
        }
        statusLabels.clear();
        devices.removeAll();
        devices.revalidate();
        devices.repaint();
    }

    private JPanel createDevicePanel(final Registration registration) {
        final JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createTitledBorder(registration.external.getName()
                + " — " + registration.external.getScriptPath()));

        final JLabel status = new JLabel("Status: loading...");
        statusLabels.put(registration.external, status);
        panel.add(status);

        for (final Action action : registration.actions) {
            panel.add(createActionControl(action));
        }
        return panel;
    }

    private JPanel createActionControl(final Action action) {
        if (action.getArguments().isEmpty()) {
            final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            final JButton button = new JButton(action.getName());
            button.addActionListener(event -> runAction(action, new Object[0]));
            panel.add(button);
            return panel;
        }

        if (action.getArguments().size() == 1 && action.getArguments().get(0).hasRange()) {
            return createSliderControl(action, action.getArguments().get(0));
        }

        return createTextControl(action);
    }

    private JPanel createSliderControl(final Action action, final ActionArgument argument) {
        final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JSlider slider = new JSlider(argument.getMinimum(), argument.getMaximum(),
                argument.getMinimum());
        slider.setMajorTickSpacing(Math.max(1,
                (argument.getMaximum() - argument.getMinimum()) / 4));
        slider.setPaintTicks(true);
        final JLabel value = new JLabel(String.valueOf(slider.getValue()));
        slider.addChangeListener(event -> value.setText(String.valueOf(slider.getValue())));

        final JButton apply = new JButton(action.getName());
        apply.addActionListener(event -> runAction(action,
                new Object[] { Integer.valueOf(slider.getValue()) }));
        panel.add(new JLabel(action.getName() + " (" + argument.getName() + ")"));
        panel.add(slider);
        panel.add(value);
        panel.add(apply);
        return panel;
    }

    private JPanel createTextControl(final Action action) {
        final JPanel panel = new JPanel(new GridBagLayout());
        final List<JTextField> fields = new ArrayList<JTextField>();
        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 2, 2, 2);
        constraints.gridy = 0;

        constraints.gridx = 0;
        panel.add(new JLabel(action.getName()), constraints);
        for (final ActionArgument argument : action.getArguments()) {
            constraints.gridx++;
            panel.add(new JLabel(argument.getName()), constraints);
            final JTextField field = new JTextField(10);
            fields.add(field);
            constraints.gridx++;
            panel.add(field, constraints);
        }
        final JButton run = new JButton("Run");
        run.addActionListener(event -> {
            try {
                final Object[] values = new Object[fields.size()];
                for (int index = 0; index < fields.size(); index++) {
                    values[index] = parseValue(fields.get(index).getText(),
                            action.getArguments().get(index).getType());
                }
                runAction(action, values);
            } catch (final IllegalArgumentException exception) {
                appendOutput(exception.getMessage());
            }
        });
        constraints.gridx++;
        panel.add(run, constraints);
        return panel;
    }

    private Object parseValue(final String value, final Class<?> type) {
        try {
            if (type == String.class) {
                return value;
            }
            if (type == Integer.class) {
                return Integer.valueOf(value);
            }
            if (type == Long.class) {
                return Long.valueOf(value);
            }
            if (type == Double.class) {
                return Double.valueOf(value);
            }
            if (type == Boolean.class) {
                return Boolean.valueOf(value);
            }
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " value: " + value);
        }
        throw new IllegalArgumentException("Unsupported argument type: " + type.getName());
    }

    private void runAction(final Action action, final Object[] arguments) {
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return Initiative.INSTANCE.executeAction(action, arguments);
            }

            @Override
            protected void done() {
                try {
                    final String result = get().trim();
                    appendOutput(action.getName() + (result.isEmpty() ? " completed." : ": " + result));
                } catch (final Exception exception) {
                    appendOutput(action.getName() + " failed: " + rootMessage(exception));
                }
            }
        }.execute();
    }

    private void appendOutput(final String message) {
        output.append(message + System.lineSeparator());
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void refreshStatuses() {
        for (final Map.Entry<External, JLabel> entry :
                new ArrayList<Map.Entry<External, JLabel>>(statusLabels.entrySet())) {
            refreshStatus(entry.getKey(), entry.getValue());
        }
    }

    private void refreshStatus(final External external, final JLabel label) {
        new SwingWorker<Object, Void>() {
            @Override
            protected Object doInBackground() {
                return external.status();
            }

            @Override
            protected void done() {
                try {
                    label.setText("Status: " + String.valueOf(get()).replaceAll("\\s+", " "));
                } catch (final Exception exception) {
                    label.setText("Status unavailable: " + rootMessage(exception));
                }
            }
        }.execute();
    }

    private String rootMessage(final Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static final class Registration {
        private final External external;
        private final List<Action> actions;

        private Registration(final External external, final List<Action> actions) {
            this.external = external;
            this.actions = actions;
        }
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
