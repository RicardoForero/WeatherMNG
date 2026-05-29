package model.persistance;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import model.SensorData;
import model.SensorHandler;
import presenter.ServerPresenter;

public class FileManager {

    /**
     * Método que escribe o sobrescribe un archivo en una ruta relativa.
     *
     * @param nombreArchivo Nombre del archivo o ruta relativa (ej: "salida.txt" o "data/logs.txt")
     * @param mensaje       Contenido a escribir dentro del archivo
     */
    public static void writeFile(String nombreArchivo, String mensaje, ServerPresenter presenter ) {
        try (FileWriter writer = new FileWriter(nombreArchivo, false)) {
            writer.write(mensaje);
        } catch (IOException e) {
            presenter.onErrorLog("Persistance Error", "Error al generar o sobreescribir el archivo "+ e.getMessage());
        }
    }

    /**
     * Método que agrega contenido a un archivo en una ruta relativa.
     * Si el archivo no existe, lo crea automáticamente.
     *
     * @param nombreArchivo Nombre del archivo o ruta relativa (ej: "log.txt" o "output/registro.txt")
     * @param mensaje       Texto que se añadirá al archivo
     * @param presenter Presenter para servir de puente para logs en archivo
     */
    public static void addContent(String nombreArchivo, String mensaje, ServerPresenter presenter) {
        try (FileWriter writer = new FileWriter(nombreArchivo, true)) {
            writer.write(mensaje + System.lineSeparator());
        } catch (IOException e) {
            presenter.onErrorLog("Persistance Error", "Error al agregar contenido al archivo: " + e.getMessage());
        }
    }
    public static boolean verifyFile(String rutaArchivo) {
        File archivo = new File(rutaArchivo);
        return archivo.exists();
    }

    public static void addSensorData(SensorData s, ServerPresenter presenter) {
    File dir = new File("files");
    if (!dir.exists()) {
        dir.mkdirs(); // crea la carpeta si no existe
    }

    String ruta = "files/" + s.getName() + ".txt";

    if (!verifyFile(ruta)) {
        writeFile(ruta, buildString(s).toString(), presenter);
    } else {
        addContent(ruta, buildString(s).toString(), presenter);
    }
}
    public static StringBuilder buildString(SensorData s){
    StringBuilder sb = new StringBuilder();
    String hora = LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    sb.append("Date: ").append(LocalDate.now())
      .append(" | Hour: ").append(hora)
      .append(" | Temperature: ").append(s.getTemp())
      .append(" | Humidity: ").append(s.getHum())
      .append(" | Heat Index: ").append(s.getHeatIndex());
    return sb;
}
    public static void onError(SensorHandler s){
        
    }

}
