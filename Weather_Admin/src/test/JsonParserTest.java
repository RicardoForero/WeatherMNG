package model;

public class JsonParserTest {
    public static void main(String[] args) {
        String json = "{\"hum\":44.0,\"plants\":[{\"slot\":0,\"hum\":60.2}]}";
        float value = JsonParser.parseFloat(json, "hum");
        if (Math.abs(value - 44.0f) > 0.0001f) {
            throw new AssertionError("Se esperaba 44.0 pero se obtuvo " + value);
        }
        System.out.println("JsonParserTest passed");
    }
}
