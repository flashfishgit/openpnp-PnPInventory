package org.openpnp.machine.pnpinventory;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Preferred-height forms and button rows, matching OpenPnP's compact configuration controls. */
final class PnpInventoryUi {
    private PnpInventoryUi() { }

    static JPanel form() { return new JPanel(new GridBagLayout()); }

    static void row(JPanel panel, String label, Component field) {
        int row = panel.getComponentCount() / 2;
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(3, 5, 3, 5);
        c.anchor = GridBagConstraints.LINE_END;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(field, c);
    }

    static JPanel buttons(Component... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (Component component : components) { row.add(component); }
        return row;
    }

    static JPanel top(JComponent content) {
        JPanel wrapper = new JPanel(new java.awt.BorderLayout());
        wrapper.add(content, java.awt.BorderLayout.NORTH);
        return wrapper;
    }

    static JLabel status() {
        return new JLabel(" ") {
            @Override public Dimension getPreferredSize() {
                return new Dimension(320, super.getPreferredSize().height);
            }
            @Override public void setText(String text) {
                super.setText(text);
                setToolTipText(text);
            }
        };
    }
}
