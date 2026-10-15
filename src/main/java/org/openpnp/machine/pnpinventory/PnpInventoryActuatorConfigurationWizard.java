package org.openpnp.machine.pnpinventory;

import java.util.Arrays;
import java.util.concurrent.Callable;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.util.UiUtils;

@SuppressWarnings("serial")
public class PnpInventoryActuatorConfigurationWizard extends AbstractConfigurationWizard {
    private final PnpInventoryActuator actuator;
    private final JTextField name = new JTextField(24);
    private final JTextField address = new JTextField(24);
    private final JTextField port = new JTextField(8);
    private final JPasswordField token = new JPasswordField(24);
    private final JTextField username = new JTextField(24);
    private final JPasswordField password = new JPasswordField(24);
    private final JTextField bus = new JTextField(36);
    private final JTextField machine = new JTextField(36);
    private final JTextField machineName = new JTextField(24);
    private final JTextField requestTimeout = new JTextField(8);
    private final JTextField pollInterval = new JTextField(8);
    private final JLabel status = PnpInventoryUi.status();
    private final JLabel connectionStatus = PnpInventoryUi.status();
    private final JLabel configuredMachine = PnpInventoryUi.status();
    private final JLabel authentication = PnpInventoryUi.status();
    private final JComboBox<PnpInventoryActuator.MachineChoice> machines = new JComboBox<>();
    private final JButton syncCatalog = new JButton("Sync catalog");
    private final JButton syncAll = new JButton("Sync all slots");
    private final JButton test = new JButton("Test connection");
    private final JButton discover = new JButton("Load machines");
    private boolean loadingMachines;
    private final JButton login = new JButton("Log in");
    private final JButton logout = new JButton("Log out");

    public PnpInventoryActuatorConfigurationWizard(PnpInventoryActuator actuator) {
        this.actuator = actuator;
        JPanel panel = PnpInventoryUi.form();
        panel.setBorder(new TitledBorder("PnPInventory API"));
        add(panel, "Name", name);
        add(panel, "Address (host or https://host)", address);
        add(panel, "Port", port);
        add(panel, "Username", username);
        add(panel, "Password (not saved)", password);
        PnpInventoryUi.row(panel, "", PnpInventoryUi.buttons(login, logout, test));
        machines.setPrototypeDisplayValue(null);
        machines.setPreferredSize(new java.awt.Dimension(220, machines.getPreferredSize().height));
        PnpInventoryUi.row(panel, "Machine", PnpInventoryUi.buttons(machines, discover));
        configuredMachine.setText(actuator.getMachineName().isEmpty() ? actuator.getMachineId() : actuator.getMachineName());
        PnpInventoryUi.row(panel, "Configured machine", configuredMachine);
        PnpInventoryUi.row(panel, "Connection", connectionStatus);
        PnpInventoryUi.row(panel, "Authentication", authentication);
        PnpInventoryUi.row(panel, "OpenPnP definitions", PnpInventoryUi.buttons(syncCatalog));
        syncCatalog.setToolTipText("Upload packages, parts, nozzle tips and supported settings. Creates missing definitions and updates mapped records; does not change stock or containers.");
        syncCatalog.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            applyAction.actionPerformed(e);
            busy(true);
            status.setText("Uploading OpenPnP catalog...");
            UiUtils.submitUiMachineTask(actuator::syncCatalog, result -> {
                busy(false);
                status.setText(result);
            }, failure -> {
                busy(false);
                status.setText("Catalog sync failed; see error details");
                UiUtils.showError(failure);
            }, true);
        }));
        PnpInventoryUi.row(panel, "Slots", PnpInventoryUi.buttons(syncAll));
        PnpInventoryUi.row(panel, "Status", status);
        syncAll.setToolTipText("Scan the feeder bus and update all slot placeholders using this actuator. Empty slots lose their part assignment.");
        syncAll.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            applyAction.actionPerformed(e);
            busy(true);
            status.setText("Scanning bus and synchronising slots...");
            UiUtils.submitUiMachineTask(actuator::syncAllSlots, result -> {
                busy(false);
                status.setText(result);
                if (org.openpnp.gui.MainFrame.get() != null) {
                    for (org.openpnp.spi.Feeder feeder : org.openpnp.model.Configuration.get().getMachine().getFeeders()) {
                        if (feeder instanceof PnpInventorySlotFeeder
                                && ((PnpInventorySlotFeeder) feeder).getInventoryActuator() == actuator) {
                            org.openpnp.gui.MainFrame.get().getFeedersTab().refresh(feeder);
                        }
                    }
                }
            }, failure -> {
                busy(false);
                status.setText("Sync failed; see error details");
                UiUtils.showError(failure);
            }, true);
        }));
        authentication.setText(actuator.authenticationStatus());
        status.setText("Not checked; test the connection first");
        JPanel advanced = PnpInventoryUi.form();
        advanced.setBorder(new TitledBorder("Advanced connection settings"));
        add(advanced, "Bearer token (filled by login)", token);
        add(advanced, "Bus UUID (optional for a single bus)", bus);
        add(advanced, "Machine name (alternative to UUID)", machineName);
        add(advanced, "Machine UUID", machine);
        add(advanced, "HTTP request timeout (ms)", requestTimeout);
        add(advanced, "Status polling interval (ms)", pollInterval);
        requestTimeout.setToolTipText("Limits one HTTP request. Maximum feed completion wait is configured in each feeder.");
        pollInterval.setToolTipText("Time between operation status requests; does not change the feed completion timeout.");
        JCheckBox showAdvanced = new JCheckBox("Advanced");
        advanced.setVisible(false);
        showAdvanced.addActionListener(e -> {
            advanced.setVisible(showAdvanced.isSelected());
            contentPanel.revalidate();
        });
        test.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            applyAction.actionPerformed(e);
            connectionStatus.setText("Checking connection...");
            runApiAction(() -> {
                try {
                    String result = actuator.testConnection();
                    javax.swing.SwingUtilities.invokeLater(() -> connectionStatus.setText(result));
                    return "Connection checked";
                }
                catch (Exception failure) {
                    javax.swing.SwingUtilities.invokeLater(() -> connectionStatus.setText("Connection test failed"));
                    throw failure;
                }
            });
        }));
        discover.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            applyAction.actionPerformed(e);
            loadMachines();
        }));
        machines.addActionListener(e -> {
            if (!loadingMachines && machines.getSelectedItem() instanceof PnpInventoryActuator.MachineChoice) {
                PnpInventoryActuator.MachineChoice choice = (PnpInventoryActuator.MachineChoice) machines.getSelectedItem();
                machine.setText(choice.id);
                configuredMachine.setText(choice.toString());
                machineName.setText(""); // Stable UUID survives a backend display-name change.
            }
        });
        token.setToolTipText("The session token is saved in the OpenPnP configuration and reused until expiry. Passwords are not saved.");
        login.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            applyAction.actionPerformed(e);
            char[] enteredPassword = password.getPassword();
            password.setText("");
            runApiAction(() -> {
                try {
                    actuator.login(new String(enteredPassword));
                }
                finally {
                    Arrays.fill(enteredPassword, '\0');
                }
                return "Logged in; expires " + actuator.getSessionExpiresAt();
            });
        }));
        logout.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            applyAction.actionPerformed(e);
            runApiAction(() -> {
                actuator.logout();
                return "Logged out";
            });
        }));
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.add(panel);
        container.add(showAdvanced);
        container.add(advanced);
        contentPanel.setLayout(new java.awt.BorderLayout());
        contentPanel.add(container, java.awt.BorderLayout.NORTH);
    }

    /** Login and lookup work while the machine is disabled, without blocking Swing's event thread. */
    private void runApiAction(Callable<String> action) {
        busy(true);
        status.setText("Connecting...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return action.call();
            }

            @Override
            protected void done() {
                busy(false);
                authentication.setText(actuator.authenticationStatus());
                token.setText(actuator.getAccessToken());
                status.setText("Request failed");
                UiUtils.messageBoxOnException(() -> {
                    try { status.setText(get()); }
                    catch (java.util.concurrent.ExecutionException e) {
                        status.setText("Failed: " + e.getCause().getMessage());
                        throw new Exception(e.getCause().getMessage(), e.getCause());
                    }
                });
            }
        }.execute();
    }

    private void busy(boolean value) {
        login.setEnabled(!value);
        logout.setEnabled(!value);
        test.setEnabled(!value);
        discover.setEnabled(!value);
        syncAll.setEnabled(!value);
        syncCatalog.setEnabled(!value);
        machines.setEnabled(!value);
        for (JTextField field : new JTextField[] {address, port, username, password, token, bus, machine,
                machineName, requestTimeout, pollInterval}) {
            field.setEnabled(!value);
        }
        applyAction.setEnabled(!value);
    }

    private void loadMachines() {
        busy(true);
        status.setText("Loading authorized machines...");
        new SwingWorker<java.util.List<PnpInventoryActuator.MachineChoice>, Void>() {
            @Override protected java.util.List<PnpInventoryActuator.MachineChoice> doInBackground() throws Exception {
                return actuator.discoverMachines();
            }
            @Override protected void done() {
                busy(false);
                UiUtils.messageBoxOnException(() -> {
                    try {
                        java.util.List<PnpInventoryActuator.MachineChoice> choices = get();
                        loadingMachines = true;
                        machines.removeAllItems();
                        for (PnpInventoryActuator.MachineChoice choice : choices) { machines.addItem(choice); }
                        machines.setSelectedItem(null);
                        for (PnpInventoryActuator.MachineChoice choice : choices) {
                            if (choice.id.equals(actuator.getMachineId())) { machines.setSelectedItem(choice); }
                        }
                        status.setText(choices.isEmpty() ? "No authorized machines found" : "Select your machine, then Apply");
                    }
                    catch (java.util.concurrent.ExecutionException e) {
                        status.setText("Machine discovery failed");
                        throw new Exception(e.getCause().getMessage(), e.getCause());
                    }
                    finally { loadingMachines = false; }
                });
            }
        }.execute();
    }

    private void add(JPanel panel, String label, JTextField field) {
        PnpInventoryUi.row(panel, label, field);
    }

    @Override
    public void createBindings() {
        IntegerConverter integer = new IntegerConverter();
        addWrappedBinding(actuator, "name", name, "text");
        addWrappedBinding(actuator, "address", address, "text");
        addWrappedBinding(actuator, "port", port, "text", integer);
        addWrappedBinding(actuator, "username", username, "text");
        addWrappedBinding(actuator, "accessToken", token, "text");
        addWrappedBinding(actuator, "busId", bus, "text");
        addWrappedBinding(actuator, "machineId", machine, "text");
        addWrappedBinding(actuator, "machineName", machineName, "text");
        addWrappedBinding(actuator, "requestTimeoutMilliseconds", requestTimeout, "text", integer);
        addWrappedBinding(actuator, "pollIntervalMilliseconds", pollInterval, "text", integer);
    }
}
