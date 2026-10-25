# ADR 0001: один VPN и общий native router

Accepted 2026-10-16. Пользователь разрешил GPLv3 приложение со всеми движками.

Выбраны собственные Kotlin UI/domain и HEV→Xray→selected transport. Veil подтверждает работоспособность формы embedding на уровне исходников; его полный UI/services не переносится. Transport APIs завернуты в adapter; зависимости pinned, одна gomobile AAR. Android VpnService единственный владелец TUN. DNS/routing не мигрируют между движками. TUN сохраняется при failover, TCP/UDP sessions могут рваться.

Отклонены полный fork без аудита логов/секретов, собственный tun2socks, несколько VPN services и routing DIRECT на удалённом сервере. Sing-box сохраняется как запасной вариант, если Xray DNS окажется недостаточен, но его добавление требует отдельного ADR и native dependency review.

Критерий пересмотра: невозможность удовлетворить leak/DNS tests выбранным core, неприемлемый расход памяти либо несовместимость native dependencies. Не переключать архитектуру только ради упрощения UI.
