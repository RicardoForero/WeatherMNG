# ═══════════════════════════════════════════════════════════════════════
#  ESP32 Plant Monitor — ESP-IDF Project
#  Compatible con WeatherServer v4 (protocolo multi-planta)
#
#  Build:  idf.py set-target esp32 && idf.py build
#  Flash:  idf.py -p COMx flash monitor
# ═══════════════════════════════════════════════════════════════════════

cmake_minimum_required(VERSION 3.16)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)

project(esp32_plant_monitor)

