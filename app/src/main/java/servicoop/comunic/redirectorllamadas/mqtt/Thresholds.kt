package servicoop.comunic.redirectorllamadas.mqtt

object Thresholds {
    // Politica local de color de salud global
    // rojo: 0..ROJO
    // naranja: (ROJO..AMARILLO)
    // verde: >= AMARILLO
    const val ROJO = 60.0
    const val AMARILLO = 80.0
}