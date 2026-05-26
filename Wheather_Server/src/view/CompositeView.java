package view;

import model.SensorData;

import java.util.List;

/**
 * Vista compuesta: redirige cada evento a múltiples vistas simultáneamente.
 *
 * Uso típico:
 * <pre>
 *   IServerView view = new CompositeView(new ConsoleView(), new FileLogView());
 *   ServerPresenter presenter = new ServerPresenter(view);
 * </pre>
 */
public class CompositeView implements IServerView {

    private final List<IServerView> views;

    public CompositeView(IServerView... views) {
        this.views = List.of(views);
    }

    @Override public void log(String m)                                    { views.forEach(v -> v.log(m)); }
    @Override public void onSensorConnected(SensorData s, int t)           { views.forEach(v -> v.onSensorConnected(s, t)); }
    @Override public void onSensorDisconnected(SensorData s, int a)        { views.forEach(v -> v.onSensorDisconnected(s, a)); }
    @Override public void onReadingReceived(SensorData s)                  { views.forEach(v -> v.onReadingReceived(s)); }
    @Override public void onSensorStale(SensorData s)                      { views.forEach(v -> v.onSensorStale(s)); }
    @Override public void onAdminConnected(String ip, int t)               { views.forEach(v -> v.onAdminConnected(ip, t)); }
    @Override public void onAdminDisconnected(String ip, int r)            { views.forEach(v -> v.onAdminDisconnected(ip, r)); }
    @Override public void onError(String ctx, String msg)                  { views.forEach(v -> v.onError(ctx, msg)); }
    @Override public void onServerStarted(int port)                        { views.forEach(v -> v.onServerStarted(port)); }
}
