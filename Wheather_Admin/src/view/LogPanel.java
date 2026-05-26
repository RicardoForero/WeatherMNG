package view;

import view.AdminDashboardFrame;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static model.AppColors.*;

/**
 * Panel lateral de eventos con scroll y coloreado de entradas.
 */
public class LogPanel extends JPanel {

    private final DefaultListModel<String> model = new DefaultListModel<>();

    public LogPanel() {
        setLayout(new BorderLayout());
        setBackground(C_BG1);
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, C_BORDER));

        JLabel title = new JLabel("  EVENTOS ADMIN");
        title.setFont(new Font("Monospaced", Font.BOLD, 10));
        title.setForeground(C_MUTED);
        title.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
            BorderFactory.createEmptyBorder(8, 0, 8, 0)));
        title.setBackground(C_BG1);
        title.setOpaque(true);

        JList<String> list = new JList<>(model);
        list.setBackground(C_BG1);
        list.setForeground(C_MUTED);
        list.setFont(new Font("Monospaced", Font.PLAIN, 10));
        list.setFixedCellHeight(18);
        list.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        list.setCellRenderer(new LogCellRenderer());

        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setBackground(C_BG1);
        sp.getViewport().setBackground(C_BG1);
        AdminDashboardFrame.styleScrollBar(sp.getVerticalScrollBar());

        add(title, BorderLayout.NORTH);
        add(sp,    BorderLayout.CENTER);
    }

    public void addEntry(String msg) {
        SwingUtilities.invokeLater(() -> {
            String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            model.add(0, ts + "  " + msg);
            if (model.size() > 300) model.remove(model.size() - 1);
        });
    }

    /* ── Renderer ─────────────────────────────────────── */
    private static class LogCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focused) {
            JLabel lb = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
            String s = value.toString();
            lb.setBackground(index % 2 == 0 ? C_BG1 : new Color(14, 22, 46));
            lb.setForeground(
                s.contains("✓") || s.contains("+") ? C_ACCENT :
                s.contains("✗") || s.contains("!") ? C_HOT    :
                s.contains("★")                     ? C_ADMIN  : C_MUTED);
            lb.setFont(new Font("Monospaced", Font.PLAIN, 10));
            lb.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            return lb;
        }
    }
}
