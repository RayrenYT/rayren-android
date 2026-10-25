package com.rayren.domain

object ImportLimits {
    const val MAX_PROFILES = 5000
    const val MAX_BYTES = 16 * 1024 * 1024
    const val VAULT_BYTES = 64 * 1024 * 1024
    const val SIZE_MESSAGE = "Подписка больше 16 МБ"
    const val COUNT_MESSAGE = "В одной подписке допускается до 5000 профилей"
}
