package com.rayren.domain

enum class RouteState(val title:String) { HEALTHY("Работают"), UNKNOWN("Не проверены"), FAILED("Не отвечают") }
object RouteEvidence {
    const val FRESH_MS=300000L
    fun state(probe:ProbeResult?,now:Long=System.currentTimeMillis()):RouteState {
        if(probe==null||now-probe.at !in 0..FRESH_MS)return RouteState.UNKNOWN
        return when(probe.state){ProbeState.OK->RouteState.HEALTHY;ProbeState.FAILED->RouteState.FAILED;else->RouteState.UNKNOWN}
    }
}
enum class DecisionReason(val text:String) {
    HTTPS_SUCCESS("Маршрут прошёл HTTPS-проверку в текущей сети."),
    AUTO_ORDER("Авто выбирает доступный обычный маршрут. Равные кандидаты перемешиваются."),
    PING_ORDER("Выбор из проверенных кандидатов с учётом задержки и недавних ошибок."),
    MANUAL("Этот сервер выбран вами вручную."),
    RESERVE_FALLBACK("Обычные подходящие маршруты не прошли проверку. Используется резерв БС."),
    ONLY_RESERVES("В выбранных подписках и регионе доступны только резервные маршруты."),
    RESERVE_NOT_NEEDED("Используется обычный маршрут; БС оставлен в резерве."),
    LIMITED_EVIDENCE("Результат основан на HTTPS-проверке. Успех всех приложений ещё не подтверждён.")
}
enum class DecisionConfidence { MEDIUM, LOW }
data class ExplainableDecision(
    val selectedRouteId:String,
    val reasons:List<DecisionReason>,
    val latencyMs:Long?,
    val recordedAt:Long,
    val confidence:DecisionConfidence=DecisionConfidence.MEDIUM,
    val failedAlternatives:Int=0,
)
