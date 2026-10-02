package presenter ;

import javax.swing.*;
import model.SensorModel;
import view.SensorView;

/**
 * PUNTO DE ENTRADA — Ensambla las tres capas  y arranca la aplicación.
 *
 *   Model  : SensorModel  (estado + TCP)
 *   View   : SensorView   (Swing JFrame)
 *   Presenter: SensorPresenter (lógica de coordinación)
 */
public class Main {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            SensorModel     model     = new SensorModel();
            SensorView      view      = new SensorView();
            new SensorPresenter(model, view);   // Presenter ensambla todo
            view.setVisible(true);
        });
    }
}
