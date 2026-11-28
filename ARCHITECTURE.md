# Карта решений Phoenix

Перед добавлением нового правила найди здесь, кто уже отвечает на этот вопрос, и расширь его, а не заводи второе. Искать по именам: номера строк устаревают. Файлы лежат в `app/src/main/java/com/phoenix/app`.

| Вопрос | Кто решает | Файл |
|---|---|---|
| Сеть сейчас нестабильна? (единое состояние) | `NetState` | `NetState.kt` |
| Какая сеть и когда она сменилась (эпоха, факты) | `NetEpoch`, `EpochFact` | `NetEpoch.kt` |
| Это обрыв сети или вина сервера? Что делать | `HealthDecision.decide` | `HealthDecision.kt` |
| Один затяжной обрыв = один эпизод | `OutageEpisode` | `OutageEpisode.kt` |
| Засчитывать ли сбой связи на сервер, когда начался обрыв | `HealthGate`, `OutageStart`, `NetFlap`, `DirectControl` | `HealthGate.kt` |
| Как часто и за какое время менять сервер, порядок очереди | `SwitchStorm`, `FailoverBudget`, `ReadyFirst`, `QueueMix` | `SwitchControl.kt` |
| Сколько проверок одновременно, когда стартовать | `MemGuard`, `ColdStart` | `ProbeLoad.kt` |
| Как читать ответ проверки (сертификат, регион, коды сайта) | `TlsOutcome`, `RegionVote`, `Soft` | `CheckVerdicts.kt` |
| Что написано в названии конфига (белые/чёрные, спецназначение) | `ListHint` | `ListHint.kt` |
| Режим «чёрные / белые списки», когда менять | `ModeDetector`, `ModeGate`, `WobbleGuard` | `ModeDetector.kt`, `ModeGate.kt` |
| Сервер выбыл на время (страйки, 24 ч) | `Quarantine` | `Quarantine.kt` |
| Порядок проверки источников и серверов | `SourceBench`, `ServerSearch` | `SourceBench.kt`, `ServerSearch.kt` |
| Порядок кандидатов под цель | `Ranking` (+ теневая оценка `RankScore`) | `Ranking.kt`, `RankScore.kt` |
| Список целей, одинаковый во всех режимах | `TargetStore`, `TargetSync` | `Targets.kt`, `TargetSyncIO.kt` |
| Проверка целей, план маршрутов | `TargetVerifier`, `PlanBuilder` | `TargetPlan.kt` |
| Путь загрузки источника (напрямую / через VPN) в одном сборе; контрольная проверка VPN перед отказом от него и повторная через 15 с, пока путь закрыт; повтор источников после возврата VPN | `FetchRoute` | `FetchRoute.kt`, использует `Collector.fetchWithRetry` |
| Открывается ли цель «мимо VPN» в текущей сети | `DirectLearn` (ключ `T:id`, по сетям), проверяет `DirectProbe`, применяет `PlanBuilder.routes(directBlocked)` | `DirectLearn.kt`, `DirectProbe.kt`, `TargetPlan.kt`, `PhoenixVpn.currentRoutes` |
| Какие конфиги прошлого пула переносить в новый сбор (не мёртвые; источник ещё в списке) | `CarryOver` (+ `carry_sources.tsv` «конфиг → источник») | `CarryOver.kt`, применяет `Collector.kt` |
| Причина кода 0 у источника (класс ошибки, время, байты) для строки `FAIL` | `FetchFailure` | `FetchRoute.kt`, вызывает `Collector.kt` |
| Какие корневые сертификаты доверены в Lite (системные + ISRG X1/X2 с проверкой отпечатка) и чей издатель назван в ошибке TLS | `TlsRoots` | `TlsRoots.kt`, ставит `LiteEnv.installTls` |
| Лимиты, принудительно выключенные функции и значения по умолчанию в сборке Lite (проб, TCP, перепроверка, сбор, память, режим, цели, обновления) | `LiteProfile` | `LiteProfile.kt`; применяют `AppSettings`, `Mode`, `Collector`, `Recheck`, `ProbeLoad`, `Backup` |
| Как обновить запись проверки «живого» с той же датой (не стирая сервисы и скорость) | `CheckUpdate` | `CheckUpdate.kt` |
| Статистика проб за проход перепроверки (ошибки ядра, время мёртвых, задержки, запасной адрес) для журнала | `ProbeStats` | `ProbeStats.kt` |
| Продолжение круга перепроверки после обрыва (файл `recheck_round.txt`, какие конфиги уже проверены) | `RecheckRound` | `RecheckRound.kt` |
| Сколько замеров режима нужно для смены (возврат к привычному режиму сети — один) и менять ли режим вместо поиска сервера | `ModeDecider.needFor` / `FailoverGate.modeInsteadOfServer` | `BlockDetector.kt`, `RunGates.kt`; память сети — `ModeHint.kt` (два счётчика WL/BL) |
| Строки журнала о времени смены сервера (кто просил, очередь, подготовка, поиск) | `SwitchTiming` | `SwitchTiming.kt` |
| Автоудаление источника по 404/410 → `SourcePolicy.definitiveGone` | `SourcePolicy` (+ `planGone`, лимит 5; неокончательный код не стирает запомненный 404/410) | `SourceState.kt`, применяет `Collector.kt` |
| Список сервисов для проверки | `CheckServices` (+ `ServiceBlocks`, `GoogleAi`, `GoogleDiag`, `GeoRules`: адреса сейчас в нескольких местах, свести в один каталог) | `ServiceCheck.kt`, `ServiceBlocks.kt`, `AppDomains.kt`, `GoogleDiag.kt`, `RegionBlock.kt` |
| Кнопка «Остановить», цепочка этапов | `StopRequest` | `StopRequest.kt` |
| Смена и перебор серверов в службе | расширения `PhoenixVpnService` | `ServiceFailover.kt` |

## Ограничители и ворота («можно ли запускать сейчас»)
Их много, часть отвечает на близкие вопросы. Перед новым ограничителем проверь, нельзя ли расширить один из этих.

| Вопрос | Кто решает | Файл |
|---|---|---|
| Сколько задач одновременно по загрузке роутера (мягкий старт, откат) | `AdaptiveGate`, `GatewayLoad`, `GatewayProbe` | `AdaptiveGate.kt`, `GatewayProbe.kt` |
| Сколько проверок по занятой памяти | `MemGuard` | `ProbeLoad.kt` |
| Лимит фоновых проверок по мобильной сети | `BackgroundBudgetGate` | `BackgroundBudgetGate.kt` |
| Когда идти тяжёлым фоновым задачам (зарядка, давно не запускались) | `HeavyGate` | `HeavyGate.kt` |
| Нужен ли автоматический перебор серверов | `FailoverGate` | `RunGates.kt` |
| Не изменились ли сеть или режим за время долгой работы | `RunGuard` | `RunGates.kt` |
| Можно ли верить итогам массовой перепроверки (сети не было, а не серверы умерли) | `RecheckGuard` | `RunGates.kt` |
| Запускать ли перепроверку после сбора (ручной «Собрать» сильнее настройки) | `RecheckGate` | `RecheckGate.kt` |
| Чей результат определения режима засчитывать, когда замеры перекрылись (последний запущенный) | `DetectSeq` | `DetectSeq.kt` |
| Один ли прямой замер идёт при сбое связи (второй вызов ждёт первый) и годен ли замер, сделанный заранее | `SharedMeasure`, `HealthGate.aheadUsable` | `SharedMeasure.kt`, `HealthGate.kt` |
| Перезагружать ли ядро после смены сети (сервер не сменили за время проверки) | `NetReload` | `NetReload.kt` |
| Сколько автосмен подряд (шторм смен) | `SwitchStorm`; рядом `AutoVetoGate` (смены по итогам проверки Google) | `SwitchControl.kt`, `GoogleDiag.kt` |
| Частые смены сети подтверждают режим дольше | `FlapGuard` (рядом `NetState`, `NetFlap`) | `HealthGate.kt` |
| Сервисы, закрытые по стране выхода | `GeoGate` | `ServiceBlocks.kt` |

Известные пересечения, разобрать после проверки на телефоне: `SwitchStorm` и `AutoVetoGate` оба ограничивают число автосмен; параллельность ограничивают `AdaptiveGate`, `MemGuard`, `BackgroundBudgetGate`, `HeavyGate` и пул проверок службы; `FlapGuard` и `NetState` оба считают частые смены сети.

## Хранилища (данные, не решения)
`CheckStore` (результаты проверок), `ExitStore` (настоящая страна выхода), `LastGoodStore` (последний рабочий сервер), `BypassStore` (обход VPN по режимам), `PoolPolicyStore` (пожелания к выбору сервера), `ActivityStore` (часы активности), `ArmStore` (обучение), `UndoStore` (откат автоправок за сутки).

## Правила для правок
- Разбивка файлов: код только переезжает, исправления отдельно.
- Новое поле состояния сети — в `NetState`, не в `ModeDetector` и не в службе.
- Новый список адресов сервисов — не создавать, добавлять в каталог сервисов.
- Файл называется по предмету («Ranking»), а не по цели (был «Resilience», в нём лежало всё подряд).
