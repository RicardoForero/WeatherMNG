package model;

/**
 * Snapshot de estadísticas globales del servidor.
 */
public record ServerStats(int totalMessages, int activeSensors, int adminCount) {}
