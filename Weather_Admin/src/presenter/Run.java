package presenter;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import model.DashboardModel;
import view.AdminDashboardFrame;

public class Run {
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            DashboardModel     model     = new DashboardModel();
            AdminDashboardFrame view      = new AdminDashboardFrame();
            DashboardPresenter presenter = new DashboardPresenter(model, view);
            view.setPresenter(presenter);
            view.setVisible(true);
        });
    }
}
