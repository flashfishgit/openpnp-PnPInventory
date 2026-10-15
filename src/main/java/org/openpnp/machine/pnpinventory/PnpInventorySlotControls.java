package org.openpnp.machine.pnpinventory;

import javax.swing.BoxLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openpnp.events.FeederSelectedEvent;
import org.openpnp.gui.FeedersPanel;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Feeder;
import org.openpnp.util.UiUtils;

/** Read-only discovery and previews run off the EDT; changes and motion use the machine queue. */
@SuppressWarnings("serial")
class PnpInventorySlotControls extends JPanel {
    private final PnpInventorySlotFeeder feeder;
    private final Runnable save;
    private final List<JButton> buttons = new ArrayList<>();
    private final JLabel status = PnpInventoryUi.status();
    private final JLabel job = PnpInventoryUi.status();
    private final JPanel recovery = new JPanel();
    private final JLabel recoveryStatus = PnpInventoryUi.status();
    private final JButton resume;
    private final JButton clear;
    private final java.beans.PropertyChangeListener operationListener = this::operationChanged;

    private void operationChanged(java.beans.PropertyChangeEvent event) {
        SwingUtilities.invokeLater(() -> {
            job.setText(feeder.jobStatus());
            if ("inventoryFailure".equals(event.getPropertyName())) {
                recovery.setVisible(true);
                recoveryStatus.setText("Feed stopped. Check status to see valid recovery actions.");
                resume.setEnabled(false);
                clear.setEnabled(false);
                revalidate();
            }
        });
    }

    @Override public void addNotify() {
        super.addNotify();
        feeder.addPropertyChangeListener(operationListener);
    }

    @Override public void removeNotify() {
        feeder.removePropertyChangeListener(operationListener);
        super.removeNotify();
    }

    PnpInventorySlotControls(PnpInventorySlotFeeder feeder, Runnable save, JTextField slotNumber,
            JComboBox<PnpInventoryActuator> actuatorChoice) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        recovery.setLayout(new BoxLayout(recovery, BoxLayout.Y_AXIS));
        this.feeder = feeder;
        this.save = save;
        setBorder(new TitledBorder("Slot setup and operation"));
        JLabel conflict = PnpInventoryUi.status();
        Runnable checkConflict = () -> {
            try {
                PnpInventoryActuator actuator = (PnpInventoryActuator) actuatorChoice.getSelectedItem();
                int slot = Integer.parseInt(slotNumber.getText());
                PnpInventoryActuator.validateSlot(slot);
                String warning = actuator == null ? "Select an API actuator" : feeder.slotConflict(actuator, slot);
                conflict.setText(warning.isEmpty() ? "Slot is not used by another OpenPnP feeder" : warning);
            }
            catch (RuntimeException e) { conflict.setText("Enter a slot from 0 to 254"); }
        };
        slotNumber.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { checkConflict.run(); }
            public void removeUpdate(DocumentEvent e) { checkConflict.run(); }
            public void changedUpdate(DocumentEvent e) { checkConflict.run(); }
        });
        actuatorChoice.addActionListener(e -> checkConflict.run());
        add(PnpInventoryUi.buttons(conflict));
        add(PnpInventoryUi.buttons(button("Sync component", () -> preview()), button("Check status", this::refresh)));
        job.setText(feeder.jobStatus());
        add(PnpInventoryUi.buttons(job));
        add(PnpInventoryUi.buttons(status));
        recovery.setBorder(new TitledBorder("Recover saved feed"));
        recovery.add(PnpInventoryUi.buttons(recoveryStatus));
        resume = button("Resume saved operation", () -> {
            save.run();
            UiUtils.submitUiMachineTask(() -> {
                if (!connection().hasPendingFeed(feeder.getId())) {
                    throw new IllegalStateException("No saved feed remains. Check status again.");
                }
                Configuration.get().getMachine().getDefaultHead().moveToSafeZ();
                if (feeder.isFeedAfterPick()) { feeder.resumePendingFeed(); }
                else { FeedersPanel.feedFeeder(feeder); }
                SwingUtilities.invokeLater(this::refresh);
            });
        });
        clear = button("Clear resolved operation", () -> {
            save.run();
            if (JOptionPane.showConfirmDialog(this,
                    "Confirm the tape position has been checked and corrected before clearing the resolved operation.",
                    "Recover feed", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) { return; }
            UiUtils.submitUiMachineTask(() -> {
                feeder.clearResolvedFeed();
                SwingUtilities.invokeLater(this::refresh);
            });
        });
        recovery.add(PnpInventoryUi.buttons(resume, clear));
        resume.setEnabled(false);
        clear.setEnabled(false);
        recovery.setVisible(false);
        add(recovery);
        // Only inspect the local journal on construction; never connect automatically.
        try {
            if (feeder.getInventoryActuator() != null && connection().hasPendingFeed(feeder.getId())) {
                recovery.setVisible(true);
                recoveryStatus.setText("Saved operation found. Check status to see valid recovery actions.");
            }
        }
        catch (Exception e) { status.setText(e.getMessage()); }
    }

    private PnpInventoryActuator connection() {
        PnpInventoryActuator actuator = feeder.getInventoryActuator();
        if (actuator == null) { throw new IllegalStateException("Select an API actuator first."); }
        return actuator;
    }

    private JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        buttons.add(button);
        button.addActionListener(e -> UiUtils.messageBoxOnException(action::run));
        return button;
    }

    private void refresh() {
        save.run();
        resume.setEnabled(false);
        clear.setEnabled(false);
        background(feeder::recoveryState, state -> {
            job.setText(feeder.jobStatus());
            boolean pending = !"none".equals(state);
            recovery.setVisible(pending);
            status.setText(pending ? "A saved feed needs attention" : "No saved feed. Assignment and job are checked before feeding.");
            recoveryStatus.setText("Operation: " + state + ("uncertain".equals(state) || "unknown".equals(state)
                    ? ". Resolve in PnPInventory, then check status again." : ""));
            resume.setEnabled(java.util.Arrays.asList("queued", "dispatched", "running", "succeeded").contains(state));
            clear.setEnabled(java.util.Arrays.asList("failed", "cancelled", "reconciled").contains(state));
            revalidate();
        });
    }

    private void preview() {
        save.run();
        List<PnpInventorySlotFeeder> selected = java.util.Collections.singletonList(feeder);
        background(() -> {
            List<PnpInventorySlotFeeder.ComponentSync> plans = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (PnpInventorySlotFeeder candidate : selected) {
                try { plans.add(candidate.previewComponent()); }
                catch (Exception e) { errors.add(candidate.getName() + ": " + e.getMessage()); }
            }
            if (!errors.isEmpty()) { throw new IllegalStateException(String.join("\n", errors)); }
            return plans;
        }, plans -> {
            JTextArea text = new JTextArea(12, 65);
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            for (PnpInventorySlotFeeder.ComponentSync plan : plans) { text.append(plan.describe() + "\n\n"); }
            if (JOptionPane.showConfirmDialog(this, new JScrollPane(text), "Review component synchronisation",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                UiUtils.submitUiMachineTask(() -> {
                    PnpInventorySlotFeeder.applySync(plans);
                    return null;
                }, ignored -> {
                    status.setText("Components synchronised");
                    Configuration.get().getBus().post(new FeederSelectedEvent(feeder, this));
                }, UiUtils::showError, true);
            }
        });
    }

    private <T> void background(Callable<T> action, Consumer<T> success) {
        for (JButton button : buttons) { button.setEnabled(false); }
        status.setText("Reading backend...");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return action.call(); }
            @Override protected void done() {
                for (JButton button : buttons) { button.setEnabled(true); }
                resume.setEnabled(false);
                clear.setEnabled(false);
                UiUtils.messageBoxOnException(() -> {
                    try { success.accept(get()); }
                    catch (java.util.concurrent.ExecutionException e) {
                        status.setText("Failed: " + e.getCause().getMessage());
                        throw new Exception(e.getCause().getMessage(), e.getCause());
                    }
                });
            }
        }.execute();
    }
}
