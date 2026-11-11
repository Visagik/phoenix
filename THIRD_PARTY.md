# Сторонние компоненты и данные

Phoenix использует чужие открытые проекты и данные. Спасибо их авторам.

| Компонент | Автор | Лицензия | Как используется |
|---|---|---|---|
| [Xray-core](https://github.com/XTLS/Xray-core) | XTLS | MPL-2.0 | Ядро туннеля. В репозиторий не входит: `libv2ray.aar` кладётся вручную (см. `app/libs/README.txt`) |
| [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) | 2dust | LGPL-3.0 | Сборка Xray для Android (`libv2ray.aar`), вызывается через рефлексию, в репозиторий не входит |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | heiher | MIT | Необязательный быстрый движок туннеля: `app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so` |
| [v2rayNG](https://github.com/2dust/v2rayNG) | 2dust | GPL-3.0 | Образец интеграции движка и список проверок; код оттуда не копировался |
| Статистика RIR (`geoip4.bin`) | NRO | CC BY 4.0 | Определение страны по IP без сети. Данные предоставлены Number Resource Organization (nro.net) |

Текст лицензии MIT для hev-socks5-tunnel: © heiher. Разрешено использование, копирование и распространение при сохранении этого уведомления об авторском праве и текста лицензии (см. репозиторий проекта).

## Источники конфигов

Phoenix сам ничего не хостит и не создаёт серверов: он скачивает **публичные списки ссылок**, которые ведут энтузиасты, и проверяет их.
Спасибо авторам, которые делают это бесплатно:

- [igareck/vpn-configs-for-russia](https://github.com/igareck/vpn-configs-for-russia)
- [FLAT447/v2ray-lists](https://github.com/FLAT447/v2ray-lists)
- [whoahaow/rjsxrd](https://github.com/whoahaow/rjsxrd)
- [vladvarp/Prometheus](https://github.com/vladvarp/Prometheus)
- [flaafix/AetrisVPN](https://github.com/flaafix/AetrisVPN)
- [zieng2/wl](https://github.com/zieng2/wl)
- [OceaniaVPN/StekloVPN](https://github.com/OceaniaVPN/StekloVPN)
- [gooseteam-hackers/GooseVPN](https://github.com/gooseteam-hackers/GooseVPN)
- [VansFenix/vpnparser](https://github.com/VansFenix/vpnparser)
- [dequar/deqwl](https://github.com/dequar/deqwl)
- [Maskkost93/kizyak-vpn-4.0](https://github.com/Maskkost93/kizyak-vpn-4.0)
- etoneya.su, vpn.akres.fun, sub.vlessfo.ru и другие авторы публичных подписок

Источники принадлежат их авторам. Если автор просит убрать свой список, достаточно открыть issue, он будет удалён из встроенных.
