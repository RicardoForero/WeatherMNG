package view;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static model.AppColors.*;

/**
 * VISTA — Panel de log de mensajes enviados.
 */
public class LogArea extends JPanel {

    private final DefaultListModel<Object[]> model = new DefaultListModel<>();

    public LogArea() {
        setLayout(new BorderLayout());
        setBackground(BG1);

        JLabel title = new JLabel("  LOG DE ENVÍOS");
        title.setFont(new Font("Monospaced", Font.BOLD, 9));
        title.setForeground(MUTED);
        title.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, BORDER),
            BorderFactory.createEmptyBorder(5, 0, 5, 0)));
        title.setBackground(BG1);
        title.setOpaque(true);

        JList<Object[]> list = new JList<>(model);
        list.setBackground(BG1);
        list.setFont(new Font("Monospaced", Font.PLAIN, 10));
        list.setFixedCellHeight(16);
        list.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        list.setCellRenderer((l, v, i, sel, foc) -> {
            Object[] row = (Object[]) v;
            JLabel lb = new JLabel((String) row[0]);
            lb.setFont(new Font("Monospaced", Font.PLAIN, 10));
            lb.setForeground((Color) row[1]);
            lb.setBackground(i % 2 == 0 ? BG1 : new Color(14, 22, 44));
            lb.setOpaque(true);
            lb.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
            return lb;
        });

        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setBackground(BG1);
        sp.getViewport().setBackground(BG1);
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(4, 0));
        sp.getVerticalScrollBar().setBackground(BG0);

        add(title,  BorderLayout.NORTH);
        add(sp,     BorderLayout.CENTER);
        setPreferredSize(new Dimension(0, 200));
    }

    public void log(String msg, Color color) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            model.add(0, new Object[]{time + " " + msg, color});
            if (model.size() > 100) model.remove(model.size() - 1);
        });
    }
}
